package com.redhat.mcp.languagetools.dap.server;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.dap.client.DapClient;
import com.redhat.mcp.languagetools.dap.client.DapEventListener;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug Adapter Protocol (DAP) server wrapper.
 * Manages lifecycle of a DAP server (debug adapter) for a workspace.
 * Similar to LspServer but for debugging instead of language features.
 */
public class DapServer {

    private static final Logger LOG = Logger.getLogger(DapServer.class);

    protected final DapServerConfig config;
    protected final URI workspaceRoot;
    protected final Path workspaceDataDir;
    protected final Path serverHome;
    protected final PathManager pathManager;

    protected Process serverProcess;
    protected IDebugProtocolServer debugServer;
    protected DapClient dapClient;
    private final ExecutorService executorService;
    private volatile ServerStatus status = ServerStatus.STOPPED;
    private volatile String statusMessage = null;
    private volatile boolean isReady = false;

    public enum ServerStatus {
        STOPPED, STARTING, RUNNING, ERROR
    }

    public DapServer(DapServerConfig config,
                     com.redhat.mcp.languagetools.ApplicationContext appContext,
                     com.redhat.mcp.languagetools.WorkspaceContext workspaceContext,
                     Path serverHome) {
        this.config = config;
        this.pathManager = appContext.getPathManager();
        this.workspaceRoot = workspaceContext.getWorkspaceRoot();
        this.workspaceDataDir = workspaceContext.getWorkspaceDataDir();
        this.serverHome = serverHome;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Start the debug adapter process and initialize it.
     */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                setStatus(ServerStatus.STARTING);
                LOG.infof("Starting DAP server: %s", config.getName());

                // Build and launch process
                List<String> command = buildCommand();
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(serverHome.toFile());

                // Set environment variables
                if (config.getEnv() != null) {
                    config.getEnv().forEach((key, value) ->
                        pb.environment().put(key, value.toString()));
                }

                serverProcess = pb.start();
                LOG.infof("DAP server process started: %s (PID: %d)",
                    config.getId(), serverProcess.pid());

                // Create launcher
                InputStream in = serverProcess.getInputStream();
                OutputStream out = serverProcess.getOutputStream();

                dapClient = new DapClient();
                Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    dapClient, in, out, executorService, wrapper -> wrapper);

                debugServer = launcher.getRemoteProxy();
                launcher.startListening();

                // Initialize
                InitializeRequestArguments initArgs = new InitializeRequestArguments();
                initArgs.setClientID("mcp-languagetools");
                initArgs.setClientName("MCP Language Tools");
                initArgs.setAdapterID(config.getId());
                initArgs.setPathFormat("path");
                initArgs.setLinesStartAt1(true);
                initArgs.setColumnsStartAt1(true);

                CompletableFuture<Capabilities> capabilitiesFuture = debugServer.initialize(initArgs);
                Capabilities capabilities = capabilitiesFuture.get();

                LOG.infof("DAP server initialized: %s", config.getName());
                setStatus(ServerStatus.RUNNING);
                setReady(true);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to start DAP server: %s", config.getId());
                setStatus(ServerStatus.ERROR);
                setStatusMessage(e.getMessage());
                throw new RuntimeException("Failed to start DAP server: " + config.getId(), e);
            }
        }, executorService);
    }

    /**
     * Stop the debug adapter.
     */
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            try {
                LOG.infof("Stopping DAP server: %s", config.getName());

                if (debugServer != null) {
                    try {
                        debugServer.disconnect(new org.eclipse.lsp4j.debug.DisconnectArguments());
                    } catch (Exception e) {
                        LOG.warnf("Error during disconnect: %s", e.getMessage());
                    }
                }

                if (serverProcess != null && serverProcess.isAlive()) {
                    serverProcess.destroy();
                    if (!serverProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        serverProcess.destroyForcibly();
                    }
                }

                setStatus(ServerStatus.STOPPED);
                setReady(false);
                LOG.infof("DAP server stopped: %s", config.getName());

            } catch (Exception e) {
                LOG.errorf(e, "Error stopping DAP server: %s", config.getId());
            }
        }, executorService);
    }

    /**
     * Build the command to launch the debug adapter.
     */
    protected List<String> buildCommand() throws IOException {
        String cmd = config.getLaunchForCurrentOS();
        if (cmd == null) {
            throw new IOException("No launch command configured for current OS");
        }

        // Substitute variables
        String resolved = cmd
            .replace("${workspace}", workspaceRoot.getPath())
            .replace("${workspaceDataDir}", workspaceDataDir.toString())
            .replace("${serverHome}", serverHome.toString());

        // Simple parsing (split by spaces, respecting quotes)
        List<String> command = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (char c : resolved.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    command.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            command.add(current.toString());
        }

        LOG.infof("DAP command: %s", String.join(" ", command));
        return command;
    }

    // Getters

    public DapServerConfig getConfig() {
        return config;
    }

    public IDebugProtocolServer getDebugServer() {
        return debugServer;
    }

    public ServerStatus getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public boolean isReady() {
        return isReady;
    }

    public Long getPid() {
        return serverProcess != null && serverProcess.isAlive()
            ? serverProcess.pid() : null;
    }

    // Setters

    protected void setStatus(ServerStatus status) {
        this.status = status;
    }

    protected void setStatusMessage(String message) {
        this.statusMessage = message;
    }

    protected void setReady(boolean ready) {
        this.isReady = ready;
    }

    /**
     * Set the event listener for DAP events (typically a DapSession).
     */
    public void setEventListener(DapEventListener listener) {
        if (dapClient != null) {
            dapClient.setEventListener(listener);
        }
    }
}
