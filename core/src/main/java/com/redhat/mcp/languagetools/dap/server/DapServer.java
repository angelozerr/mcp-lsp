package com.redhat.mcp.languagetools.dap.server;

import com.redhat.mcp.languagetools.dap.client.DapClient;
import com.redhat.mcp.languagetools.dap.client.DapEventListener;
import com.redhat.mcp.languagetools.dap.trace.DapTraceMessage;
import com.redhat.mcp.languagetools.server.ServerBase;
import com.redhat.mcp.languagetools.server.ServerStatus;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Debug Adapter Protocol (DAP) server wrapper.
 * Manages lifecycle of a DAP server (debug adapter) for a workspace.
 * Similar to LspServer but for debugging instead of language features.
 */
public class DapServer extends ServerBase<DapServerConfig> {

    private static final Logger LOG = Logger.getLogger(DapServer.class);

    protected final DapServerContext context;

    protected IDebugProtocolServer debugServer;
    protected DapClient dapClient;
    private static MessageJsonHandler jsonHandler;

    public DapServer(DapServerConfig config, DapServerContext context) {
        super(config);
        this.context = context;
    }

    /**
     * Start the debug adapter process and initialize it.
     */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            var config = super.getConfig();
            try {
                setStatus(ServerStatus.STARTING);
                LOG.infof("Starting DAP server: %s", config.getName());

                // Build and launch process
                List<String> command = buildCommand();
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(context.getDapServerHome().toFile());

                // Set environment variables
                if (config.getEnv() != null) {
                    config.getEnv().forEach((key, value) ->
                            pb.environment().put(key, value.toString()));
                }

                // Log command to trace collector
                String commandStr = String.join(" ", command);
                context.getTraceCollector().addTrace(
                        context.getWorkspaceRoot().toString(),
                        context.getSessionId(),
                        context.getSessionName(),
                        DapTraceMessage.MessageDirection.SENT,
                        "Starting DAP server: " + config.getName() + "\n" +
                                "Command: " + commandStr + "\n" +
                                "Working directory: " + context.getDapServerHome()
                );

                serverProcess = pb.start();
                LOG.infof("DAP server process started: %s (PID: %d)",
                        config.getId(), serverProcess.pid());

                // Log process started
                context.getTraceCollector().addTrace(
                        context.getWorkspaceRoot().toString(),
                        context.getSessionId(),
                        context.getSessionName(),
                        DapTraceMessage.MessageDirection.RECEIVED,
                        "DAP server process started (PID: " + serverProcess.pid() + ")"
                );

                // Create launcher with tracing
                InputStream in = serverProcess.getInputStream();
                OutputStream out = serverProcess.getOutputStream();

                dapClient = new DapClient();

                // Wrapper for tracing (like lsp4ij)
                UnaryOperator<MessageConsumer> wrapper = consumer -> message -> {
                    // Log trace
                    boolean isSent = consumer.getClass().getSimpleName().equals("StreamMessageConsumer");
                    DapTraceMessage.MessageDirection direction = isSent ?
                            DapTraceMessage.MessageDirection.SENT :
                            DapTraceMessage.MessageDirection.RECEIVED;

                    String jsonContent = toJson(message);
                    context.getTraceCollector().addTrace(
                            context.getWorkspaceRoot().toString(),
                            context.getSessionId(),
                            context.getSessionName(),
                            direction,
                            jsonContent
                    );

                    // Forward to actual consumer
                    consumer.consume(message);
                };

                Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                        dapClient, in, out, executorService, wrapper);

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
    public CompletableFuture<Void> stop2() {
        return CompletableFuture.runAsync(() -> {
            var config = super.getConfig();
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
                    if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
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
        var config = super.getConfig();
        String cmd = config.getLaunchForCurrentOS();
        if (cmd == null) {
            throw new IOException("No launch command configured for current OS");
        }

        // Substitute variables
        String resolved = cmd
                .replace("${workspace}", context.getWorkspaceRoot().getPath())
                .replace("${workspaceDataDir}", context.getWorkspaceDataDir().toString())
                .replace("${context.getDapServerHome()}", context.getDapServerHome().toString());

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

    public IDebugProtocolServer getDebugServer() {
        return debugServer;
    }

    public Long getPid() {
        return serverProcess != null && serverProcess.isAlive()
                ? serverProcess.pid() : null;
    }

    // Setters

    /**
     * Set the event listener for DAP events (typically a DapSession).
     */
    public void setEventListener(DapEventListener listener) {
        if (dapClient != null) {
            dapClient.setEventListener(listener);
        }
    }

    private static String toJson(Message message) {
        if (jsonHandler == null) {
            jsonHandler = new MessageJsonHandler(java.util.Collections.emptyMap());
        }
        return jsonHandler.serialize(message);
    }
}
