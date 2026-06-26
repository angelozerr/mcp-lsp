package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.language.LanguageDocument;
import com.redhat.mcp.languagetools.lsp.*;
import com.redhat.mcp.languagetools.lsp.client.GenericLanguageClient;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.client.LspCapability;
import com.redhat.mcp.languagetools.lsp.client.LspClientFeatures;
import com.redhat.mcp.languagetools.lsp.client.LspNotificationConstants;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;
import com.redhat.mcp.languagetools.lsp.trace.TracingMessageConsumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generic Language Server instance.
 * Works with any LSP-compliant language server based on configuration.
 */
public class LspServer {

    private static final Logger LOG = Logger.getLogger(LspServer.class);

    protected final LspServerConfig config;
    protected final URI workspaceRoot;
    protected final Path workspaceDataDir;
    protected final Path serverHome;
    protected final TracingMessageConsumer tracing;
    protected final List<LspServerConfig> allServerConfigs;
    protected final PathManager pathManager;

    protected Process serverProcess;
    protected Socket socket;
    protected LanguageServer languageServer;
    private final ExecutorService executorService;
    private final Map<String, List<Diagnostic>> diagnosticsCache = new ConcurrentHashMap<>();
    private final java.util.Set<String> openedFiles = ConcurrentHashMap.newKeySet();
    private volatile ServerStatus status = ServerStatus.STOPPED;
    private volatile String statusMessage = null;
    private boolean isSocketConnection = false;
    private InstanceFileWatcher fileWatcher;
    private LspInstanceRegistry.InstanceInfo currentInstance;
    protected volatile boolean isReady = false;
    protected com.redhat.mcp.languagetools.workspace.WorkspaceConfiguration workspaceConfiguration;
    protected ExtensionManager extensionManager;
    protected RequestRouter requestRouter;
    private java.util.function.Consumer<ServerStatus> statusChangeCallback;
    private LspClientFeatures clientFeatures;

    public RequestRouter getRequestRouter() {
        return requestRouter;
    }

    public LspServer(LspServerConfig config, LspServerContext context) {
        this.config = config;
        this.workspaceRoot = context.getWorkspaceRoot();
        this.workspaceDataDir = context.getWorkspaceDataDir();
        this.serverHome = context.getServerHome();
        this.tracing = new TracingMessageConsumer(context.getTraceCollector(), workspaceRoot.toString(), config.getId(), config.getName());
        this.executorService = Executors.newCachedThreadPool();
        this.allServerConfigs = context.getAllServerConfigs() != null ? context.getAllServerConfigs() : List.of();
        this.pathManager = context.getPathManager();

        // Create client features for managing capabilities
        this.clientFeatures = new LspClientFeatures(config);
    }

    /**
     * Set a callback to be notified when server status changes.
     */
    public void setStatusChangeCallback(java.util.function.Consumer<ServerStatus> callback) {
        this.statusChangeCallback = callback;
    }

    /**
     * Update server status and notify callback if registered.
     */
    private void setStatus(ServerStatus newStatus) {
        ServerStatus oldStatus = this.status;
        this.status = newStatus;

        // Clear status message when stopping/stopped
        if (newStatus == ServerStatus.STOPPING || newStatus == ServerStatus.STOPPED) {
            this.statusMessage = null;
        }

        LOG.infof("LspServer.setStatus: %s -> %s (callback registered: %s)",
                oldStatus, newStatus, statusChangeCallback != null);

        if (statusChangeCallback != null && oldStatus != newStatus) {
            LOG.infof("Calling status change callback for %s: %s -> %s", config.getId(), oldStatus, newStatus);
            try {
                statusChangeCallback.accept(newStatus);
            } catch (Exception e) {
                LOG.warnf(e, "Error in status change callback for %s", config.getId());
            }
        }
    }

    /**
     * Start the language server process and establish LSP communication.
     * First tries to connect to an existing instance via socket, falls back to launching a new process.
     */
    public CompletableFuture<Void> start() {
        setStatus(ServerStatus.STARTING);
        return CompletableFuture.runAsync(() -> {
            try {
                // Try to find existing instance first
                String workspacePath = Paths.get(workspaceRoot).toString();
                LspInstanceRegistry.InstanceInfo existingInstance = LspInstanceRegistry.findInstance(workspacePath, config.getId());

                if (existingInstance != null) {
                    // Connect to existing instance via socket
                    setStatus(ServerStatus.CONNECTING_TO_IDE);
                    try {
                        connectToSocket(existingInstance.port);
                        currentInstance = existingInstance;
                        LOG.infof("Connected to existing %s instance on port %d (PID: %d)",
                            config.getId(), existingInstance.port, existingInstance.pid);
                        startFileWatcher(workspacePath);
                        return;
                    } catch (IOException e) {
                        LOG.warnf("Failed to connect to existing instance on port %d, will launch new process: %s",
                            existingInstance.port, e.getMessage());
                        setStatus(ServerStatus.STARTING);
                        // Fall through to launch new process
                    }
                }

                // No existing instance found or connection failed - launch new process
                launchProcess();
                startFileWatcher(workspacePath);

            } catch (IOException e) {
                LOG.errorf(e, "Failed to start %s", config.getId());
                throw new RuntimeException("Failed to start " + config.getId(), e);
            }
        }, executorService);
    }

    /**
     * Start a MCP-managed language server process only (do not connect to IDE instance).
     */
    public CompletableFuture<Void> startManagedOnly() {
        setStatus(ServerStatus.STARTING);
        return CompletableFuture.runAsync(() -> {
            try {
                String workspacePath = Paths.get(workspaceRoot).toString();

                // Launch new process directly without checking for IDE instance
                launchProcess();
                startFileWatcher(workspacePath);

            } catch (IOException e) {
                LOG.errorf(e, "Failed to start %s", config.getId());
                throw new RuntimeException("Failed to start " + config.getId(), e);
            }
        }, executorService);
    }

    /**
     * Connect to an existing language server via socket.
     */
    private void connectToSocket(int port) throws IOException {
        LOG.infof("Connecting to %s on localhost:%d", config.getId(), port);

        socket = new Socket("localhost", port);
        isSocketConnection = true;

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // Create LSP client (subclasses can override to provide custom client)
        LanguageClient client = createLanguageClient();

        // Create LSP launcher with message tracing wrapper
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client,
                in,
                out,
                executorService,
                consumer -> message -> {
                    // Log the message
                    tracing.log(message, consumer);
                    // Forward to original consumer
                    consumer.consume(message);
                }
        );

        languageServer = launcher.getRemoteProxy();
        launcher.startListening();

        LOG.infof("Socket connection established to %s on port %d", config.getId(), port);
    }

    /**
     * Launch a new language server process.
     */
    protected void launchProcess() throws IOException {
        LOG.infof("Launching new %s process for workspace: %s", config.getId(), workspaceRoot);

        List<String> command = buildCommand();
        LOG.debugf("%s command: %s", config.getId(), String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);

        // Set environment variables
        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            pb.environment().putAll(config.getEnv());
        }

        // Set working directory
        if (config.getWorkingDirectory() != null) {
            pb.directory(Paths.get(config.getWorkingDirectory()).toFile());
        }

        // Don't redirect error stream - we want to capture it separately
        serverProcess = pb.start();
        isSocketConnection = false;

        // Read and log stderr in background, send to trace collector
        executorService.submit(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()))) {
                String line;
                StringBuilder stackTraceBuffer = new StringBuilder();
                String stackTraceTimestamp = null;

                while ((line = reader.readLine()) != null) {
                    LOG.errorf("[%s stderr] %s", config.getId(), line);

                    String trimmed = line.trim();
                    boolean isStackTraceLine = trimmed.startsWith("at ") && trimmed.contains("(") && trimmed.contains(")");
                    boolean isExceptionLine = trimmed.contains("Exception:") || trimmed.contains("Error:");

                    if (isStackTraceLine || (isExceptionLine && stackTraceBuffer.length() == 0)) {
                        // Start or continue stack trace buffering
                        if (stackTraceBuffer.length() == 0) {
                            stackTraceTimestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                        }
                        stackTraceBuffer.append(line).append("\n");
                    } else {
                        // Flush buffered stack trace if any
                        if (stackTraceBuffer.length() > 0) {
                            String errorTrace = String.format("[Error - %s] %s stderr: %s",
                                stackTraceTimestamp,
                                config.getName(),
                                stackTraceBuffer.toString().trim());
                            tracing.getCollector().addTrace(
                                workspaceRoot.toString(),
                                config.getId(),
                                config.getName(),
                                LspTraceMessage.MessageDirection.SERVER_TO_CLIENT,
                                errorTrace
                            );
                            stackTraceBuffer.setLength(0);
                            stackTraceTimestamp = null;
                        }

                        // Send current line as regular error
                        String errorTrace = String.format("[Error - %s] %s stderr: %s",
                            java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                            config.getName(),
                            line);
                        tracing.getCollector().addTrace(
                            workspaceRoot.toString(),
                            config.getId(),
                            config.getName(),
                            LspTraceMessage.MessageDirection.SERVER_TO_CLIENT,
                            errorTrace
                        );
                    }
                }

                // Flush remaining stack trace at end of stream
                if (stackTraceBuffer.length() > 0) {
                    String errorTrace = String.format("[Error - %s] %s stderr: %s",
                        stackTraceTimestamp,
                        config.getName(),
                        stackTraceBuffer.toString().trim());
                    tracing.getCollector().addTrace(
                        workspaceRoot.toString(),
                        config.getId(),
                        config.getName(),
                        LspTraceMessage.MessageDirection.SERVER_TO_CLIENT,
                        errorTrace
                    );
                }
            } catch (Exception e) {
                LOG.debugf("Error stream reader closed: %s", e.getMessage());
            }
        });

        // Create LSP client (subclasses can override to provide custom client)
        LanguageClient client = createLanguageClient();

        // GenericLanguageClient already implements Endpoint for bindRequest routing,
        // so we can pass it directly - LSP4J will scan it for @JsonNotification
        // Create LSP launcher with message tracing wrapper (like lsp4ij)
        Launcher<LanguageServer> launcher = new Launcher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer.class)
                .setInput(serverProcess.getInputStream())
                .setOutput(serverProcess.getOutputStream())
                .setExecutorService(executorService)
                .wrapMessages(consumer -> message -> {
                    // Log the message
                    tracing.log(message, consumer);
                    // Forward to original consumer
                    consumer.consume(message);
                })
                .create();

        languageServer = launcher.getRemoteProxy();
        launcher.startListening();

        LOG.infof("%s process started for workspace: %s", config.getId(), workspaceRoot);

        // Set initial status message - server is RUNNING but not ready yet
        // Will be overridden by "Ready" after initialization,
        // or by language/status notifications for servers like JDT.LS
        setStatusMessage("Not Ready");
    }

    /**
     * Initialize the language server (send LSP initialize request).
     */
    public CompletableFuture<Void> initialize() {
        // If connected to external instance (IDE), server is already initialized
        if (isSocketConnection && currentInstance != null) {
            LOG.infof("%s already initialized by IDE (port %d, PID %d)",
                     config.getId(), currentInstance.port, currentInstance.pid);
            setStatus(ServerStatus.CONNECTED_TO_IDE);
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Initializing %s for workspace: %s", config.getId(), workspaceRoot);

        InitializeParams params = new InitializeParams();
        params.setRootUri(workspaceRoot.toString());

        // Set process ID
        params.setProcessId((int) ProcessHandle.current().pid());

        WorkspaceFolder workspaceFolder = new WorkspaceFolder();
        workspaceFolder.setUri(workspaceRoot.toString());
        workspaceFolder.setName(Paths.get(workspaceRoot).getFileName().toString());
        params.setWorkspaceFolders(List.of(workspaceFolder));

        // Client capabilities
        ClientCapabilities capabilities = new ClientCapabilities();

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        workspace.setConfiguration(true);
        capabilities.setWorkspace(workspace);

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        textDocument.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
        textDocument.setCodeAction(new CodeActionCapabilities());
        textDocument.setHover(new HoverCapabilities());
        textDocument.setDefinition(new DefinitionCapabilities());
        textDocument.setReferences(new ReferencesCapabilities());
        textDocument.setDocumentSymbol(new DocumentSymbolCapabilities());
        capabilities.setTextDocument(textDocument);

        params.setCapabilities(capabilities);

        // Set trace level from global config
        params.setTrace(getTraceLevelFromConfig());

        // Prepare initialization options (hook for subclasses to add server-specific options)
        Object initOptions = prepareInitializationOptions();
        if (initOptions != null) {
            params.setInitializationOptions(initOptions);
        }

        return languageServer.initialize(params)
                .thenCompose(initResult -> {
                    LOG.infof("%s initialized for workspace: %s", config.getId(), workspaceRoot);

                    // Pass server capabilities to client features
                    clientFeatures.setServerCapabilities(initResult.getCapabilities());

                    languageServer.initialized(new InitializedParams());
                    setStatus(ServerStatus.RUNNING);
                    // For generic servers, ready after initialization
                    // Subclasses like JdtLsServer may override this behavior
                    isReady = true;
                    setStatusMessage("Ready");
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Send a request directly to the language server via JSON-RPC.
     * Used for custom LSP requests that are not standard LSP commands.
     *
     * @param method Request method (e.g., "custom/myRequest")
     * @param params Request parameters
     * @return CompletableFuture with the response
     */
    public CompletableFuture<Object> sendRequest(String method, Object params) {
        if (languageServer == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Language server not started")
            );
        }

        // Send directly via Endpoint (JSON-RPC)
        if (languageServer instanceof org.eclipse.lsp4j.jsonrpc.Endpoint) {
            org.eclipse.lsp4j.jsonrpc.Endpoint endpoint = (org.eclipse.lsp4j.jsonrpc.Endpoint) languageServer;
            return endpoint.request(method, params)
                .thenApply(result -> (Object) result);
        }

        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("Server does not support direct requests")
        );
    }

    /**
     * Send a request via workspace/executeCommand.
     * Used for routing bindRequest between servers (like vscode-java does with java.execute.workspaceCommand).
     * The target server must have registered a command handler for this method.
     *
     * @param method Request method/command (e.g., "microprofile/java/projectInfo")
     * @param params Request parameters
     * @return CompletableFuture with the response
     */
    public CompletableFuture<Object> sendCommandRequest(String method, Object params) {
        if (languageServer == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Language server not started")
            );
        }

        // Send via workspace/executeCommand (like vscode-java does)
        ExecuteCommandParams commandParams = new ExecuteCommandParams();
        commandParams.setCommand(method);

        // If params is already a list, use it; otherwise wrap in a list
        if (params instanceof java.util.List) {
            commandParams.setArguments((java.util.List<Object>) params);
        } else if (params != null) {
            commandParams.setArguments(java.util.List.of(params));
        } else {
            commandParams.setArguments(java.util.List.of());
        }

        return languageServer.getWorkspaceService()
            .executeCommand(commandParams)
            .thenApply(result -> (Object) result);
    }

    /**
     * Shutdown the language server.
     */
    public CompletableFuture<Void> shutdown() {
        LOG.infof("Shutting down %s for workspace: %s", config.getId(), workspaceRoot);

        // Set status based on current connection type
        if (isSocketConnection && currentInstance != null) {
            setStatus(ServerStatus.DISCONNECTING);
        } else {
            setStatus(ServerStatus.STOPPING);
        }

        // Stop file watcher first
        if (fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
        }

        // Clear diagnostics cache
        diagnosticsCache.clear();

        if (languageServer == null && serverProcess == null && socket == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Try graceful shutdown first
                if (languageServer != null) {
                    try {
                        languageServer.shutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
                        languageServer.exit();
                    } catch (Exception e) {
                        LOG.warnf("Graceful shutdown failed for %s: %s", config.getId(), e.getMessage());
                    }
                }

                // Close socket connection if connected via socket
                if (isSocketConnection && socket != null) {
                    try {
                        socket.close();
                        LOG.infof("Closed socket connection to %s", config.getId());
                    } catch (IOException e) {
                        LOG.warnf("Failed to close socket: %s", e.getMessage());
                    }
                }

                // Force kill process if still alive (only if we launched it)
                if (!isSocketConnection && serverProcess != null && serverProcess.isAlive()) {
                    LOG.infof("Force killing %s process", config.getId());
                    serverProcess.destroyForcibly();

                    // Wait a bit for process to die
                    if (!serverProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        LOG.errorf("Failed to kill %s process", config.getId());
                    }
                }

                // Shutdown executor
                executorService.shutdown();
                if (!executorService.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }

                LOG.infof("%s shut down for workspace: %s", config.getId(), workspaceRoot);
                setStatus(ServerStatus.STOPPED);

            } catch (Exception e) {
                LOG.errorf(e, "Error during shutdown of %s", config.getId());
                setStatus(ServerStatus.STOPPED);
            }
        }, executorService);
    }

    /**
     * Build the command to launch the language server.
     * Supports variable substitution:
     * - ${workspace} → workspace root path
     * - ${workspaceDataDir} → workspace data directory
     * - ${serverHome} → language server installation directory
     * - ${configuration} → OS-specific config directory
     * - ${DATA_DIR} → workspace data directory
     * - ${user.name} → system user name
     */
    protected List<String> buildCommand() throws IOException {
        String cmd = config.getCommandForCurrentOS();
        if (cmd == null) {
            throw new IOException("No command configured for current OS");
        }

        // Determine configuration directory based on OS
        String os = System.getProperty("os.name").toLowerCase();
        String configuration = serverHome.resolve(
            os.contains("win") ? "config_win" :
            os.contains("mac") ? "config_mac" : "config_linux"
        ).toString();

        // Substitute variables in command
        String resolved = cmd
                .replace("${workspace}", workspaceRoot.getPath())
                .replace("${workspaceDataDir}", workspaceDataDir.toString())
                .replace("${serverHome}", serverHome.toString())
                .replace("${configuration}", configuration)
                .replace("${DATA_DIR}", workspaceDataDir.toString())
                .replace("${user.name}", System.getProperty("user.name"));

        // Parse command string into arguments (simple split by spaces, respecting quotes)
        List<String> command = parseCommandLine(resolved);

        // Add any additional args from config
        for (String arg : config.getArgs()) {
            String resolvedArg = arg
                    .replace("${workspace}", workspaceRoot.getPath())
                    .replace("${workspaceDataDir}", workspaceDataDir.toString())
                    .replace("${serverHome}", serverHome.toString())
                    .replace("${configuration}", configuration)
                    .replace("${DATA_DIR}", workspaceDataDir.toString())
                    .replace("${user.name}", System.getProperty("user.name"));

            // Handle glob patterns (e.g., ${serverHome}/plugins/*.jar)
            resolvedArg = resolveGlob(resolvedArg);

            command.add(resolvedArg);
        }

        return command;
    }

    /**
     * Simple command line parser that respects quotes.
     */
    private List<String> parseCommandLine(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    /**
     * Resolve glob patterns in paths (e.g., /path/to/*.jar)
     */
    private String resolveGlob(String path) throws IOException {
        if (!path.contains("*")) {
            return path;
        }

        // Simple glob resolution: find first match
        int starIndex = path.indexOf('*');
        int lastSlash = path.lastIndexOf('/', starIndex);
        if (lastSlash == -1) {
            return path;
        }

        Path dir = Paths.get(path.substring(0, lastSlash));
        String pattern = path.substring(lastSlash + 1);

        if (!Files.exists(dir)) {
            return path;
        }

        // Find first matching file
        try (var stream = Files.list(dir)) {
            String globRegex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*");

            return stream
                    .filter(p -> p.getFileName().toString().matches(globRegex))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(path);
        }
    }

    public LanguageServer getLanguageServer() {
        return languageServer;
    }

    public Map<String, List<Diagnostic>> getDiagnosticsCache() {
        return diagnosticsCache;
    }

    public LspServerConfig getConfig() {
        return config;
    }

    public ServerStatus getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        String oldMessage = this.statusMessage;
        this.statusMessage = statusMessage;

        LOG.infof("[%s] setStatusMessage called: %s -> %s (callback: %s)",
            config.getId(), oldMessage, statusMessage, statusChangeCallback != null);

        // Notify if message changed and callback is registered
        if (statusChangeCallback != null && !java.util.Objects.equals(oldMessage, statusMessage)) {
            LOG.infof("[%s] Status message changed, firing callback", config.getId());
            // Trigger status change callback to refresh UI
            statusChangeCallback.accept(this.status);
        }
    }

    /**
     * Get the process ID of the running server (if available).
     */
    public Long getPid() {
        if (serverProcess != null && serverProcess.isAlive()) {
            return serverProcess.pid();
        }
        return null;
    }

    /**
     * Get the start command used to launch the server.
     */
    public String getStartCommand() {
        return config.getCommandForCurrentOS();
    }

    /**
     * Check if the language server is ready to handle requests.
     * For most servers, this is true after initialize() completes (status == RUNNING).
     * For servers like JDT.LS, this is determined by specific notifications (e.g., language/status).
     * Subclasses can override this method to provide custom readiness detection.
     */
    public boolean isReady() {
        return isReady;
    }

    /**
     * Wait until the server is ready, with a timeout.
     * Returns a CompletableFuture that completes when the server is ready.
     */
    public CompletableFuture<Void> waitUntilReady(long timeoutMs) {
        if (isReady) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            while (!isReady && (System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for server to be ready", e);
                }
            }
            if (!isReady) {
                throw new RuntimeException("Server not ready after " + timeoutMs + "ms");
            }
        }, executorService);
    }

    /**
     * Set the ready state of the language server.
     * Called by subclasses when they detect the server is ready (e.g., JdtLsServer on language/status).
     */
    protected void setReady(boolean ready) {
        this.isReady = ready;
    }

    public LspInstanceRegistry.InstanceInfo getCurrentInstance() {
        return currentInstance;
    }

    /**
     * Set workspace configuration (called by Workspace after server creation).
     */
    public void setWorkspaceConfiguration(com.redhat.mcp.languagetools.workspace.WorkspaceConfiguration configuration) {
        this.workspaceConfiguration = configuration;
    }

    /**
     * Get workspace configuration.
     */
    protected com.redhat.mcp.languagetools.workspace.WorkspaceConfiguration getWorkspaceConfiguration() {
        return workspaceConfiguration;
    }

    /**
     * Set extension manager (called by Workspace after server creation).
     */
    public void setExtensionManager(ExtensionManager extensionManager) {
        this.extensionManager = extensionManager;
    }

    /**
     * Get extension manager.
     */
    protected ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    /**
     * Set request router for delegating requests to other servers (bindRequest mechanism).
     */
    public void setRequestRouter(RequestRouter router) {
        this.requestRouter = router;
    }

    /**
     * Check if a file is currently opened in this server.
     */
    public boolean isFileOpened(String uri) {
        return openedFiles.contains(uri);
    }

    /**
     * Mark a file as opened.
     */
    public void markFileOpened(String uri) {
        openedFiles.add(uri);
    }

    /**
     * Mark a file as closed.
     */
    public void markFileClosed(String uri) {
        openedFiles.remove(uri);
    }

    /**
     * Start watching instance files for changes (IDE start/stop detection).
     */
    private void startFileWatcher(String workspacePath) {
        try {
            fileWatcher = new InstanceFileWatcher(
                workspacePath,
                config.getId(),
                this::handleInstanceChanged,
                this::handleInstanceRemoved
            );
            fileWatcher.start();
            LOG.infof("Started instance file watcher for %s", config.getId());
        } catch (IOException e) {
            LOG.warnf("Failed to start instance file watcher: %s", e.getMessage());
        }
    }

    /**
     * Handle when a new/updated instance is detected (e.g., IDE started).
     */
    private void handleInstanceChanged(LspInstanceRegistry.InstanceInfo newInstance) {
        // Only react if it's a different instance than our current one
        if (currentInstance != null && currentInstance.pid == newInstance.pid && currentInstance.port == newInstance.port) {
            LOG.debugf("Instance unchanged, ignoring");
            return;
        }

        LOG.infof("New instance detected (PID: %d, port: %d), switching connection...", newInstance.pid, newInstance.port);

        // If we launched our own server, stop it
        if (!isSocketConnection && serverProcess != null && serverProcess.isAlive()) {
            LOG.infof("Stopping our own server process to switch to IDE instance");
            try {
                languageServer.shutdown().get(2, java.util.concurrent.TimeUnit.SECONDS);
                languageServer.exit();
                serverProcess.destroyForcibly();
            } catch (Exception e) {
                LOG.warnf("Error stopping our server: %s", e.getMessage());
            }
        }

        // Close current socket if we're already connected
        if (isSocketConnection && socket != null) {
            try {
                socket.close();
                LOG.infof("Closed previous socket connection");
            } catch (IOException e) {
                LOG.warnf("Error closing socket: %s", e.getMessage());
            }
        }

        // Connect to new instance
        try {
            connectToSocket(newInstance.port);
            currentInstance = newInstance;

            // Re-initialize with new instance
            initialize().thenRun(() -> {
                LOG.infof("Successfully switched to new instance (PID: %d, port: %d)", newInstance.pid, newInstance.port);
            });
        } catch (IOException e) {
            LOG.errorf(e, "Failed to connect to new instance, will try to restart our own server");
            handleInstanceRemoved();
        }
    }

    /**
     * Handle when instance is removed (e.g., IDE closed).
     */
    private void handleInstanceRemoved() {
        // If we're connected via socket and the instance is gone, launch our own server
        if (isSocketConnection) {
            LOG.infof("External instance removed, launching our own server");

            // Close socket
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOG.debugf("Error closing socket: %s", e.getMessage());
                }
                socket = null;
            }

            currentInstance = null;
            isSocketConnection = false;
            languageServer = null;

            // Launch our own server
            try {
                launchProcess();
                initialize().thenRun(() -> {
                    LOG.infof("Successfully launched our own server after instance removal");
                });
            } catch (IOException e) {
                LOG.errorf(e, "Failed to launch server after instance removal");
                setStatus(ServerStatus.STOPPED);
            }
        }
    }

    /**
     * Create the language client for this server.
     * Subclasses can override this to provide custom client implementations
     * (e.g., JdtLsServer provides JdtLsLanguageClient for language/status notifications).
     */
    protected LanguageClient createLanguageClient() {
        return new GenericLanguageClient(this);
    }

    /**
     * DEPRECATED: Routing is now handled by GenericLanguageClient.request() via Endpoint interface.
     * This method is no longer used.
     */
    @Deprecated
    private Object createRoutingServiceObject_UNUSED(LanguageClient client) {
        LOG.infof("Creating routing service object for %s", config.getId());

        // Create a delegating endpoint that intercepts requests
        org.eclipse.lsp4j.jsonrpc.Endpoint delegatingEndpoint = new org.eclipse.lsp4j.jsonrpc.Endpoint() {
            private org.eclipse.lsp4j.jsonrpc.Endpoint clientEndpoint;

            @Override
            public CompletableFuture<?> request(String method, Object parameter) {
                LOG.infof("[%s] Endpoint.request() called with method: %s", config.getId(), method);

                // Handle client/registerCapability
                if (LspNotificationConstants.CLIENT_REGISTER_CAPABILITY.equals(method)) {
                    if (parameter instanceof RegistrationParams) {
                        clientFeatures.registerCapability((RegistrationParams) parameter);
                        return CompletableFuture.completedFuture(null);
                    }
                }

                // Handle client/unregisterCapability
                if (LspNotificationConstants.CLIENT_UNREGISTER_CAPABILITY.equals(method)) {
                    if (parameter instanceof UnregistrationParams) {
                        clientFeatures.unregisterCapability((UnregistrationParams) parameter);
                        return CompletableFuture.completedFuture(null);
                    }
                }

                // Check if this request should be routed to another server (bindRequest)
                BindRequestInfo bindInfo = findBindRequestInfo(method);

                if (bindInfo != null && requestRouter != null) {
                    LOG.infof("Routing bindRequest %s to server %s (mode: %s)",
                        method, bindInfo.targetServerId, bindInfo.mode);
                    return requestRouter.routeRequest(bindInfo.targetServerId, method, parameter, bindInfo.mode);
                }

                LOG.debugf("[%s] Request %s is not a bindRequest, not routing", config.getId(), method);

                // Otherwise, delegate to client endpoint
                if (clientEndpoint == null) {
                    return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Request not supported: " + method)
                    );
                }
                return clientEndpoint.request(method, parameter);
            }

            @Override
            public void notify(String method, Object parameter) {
                if (clientEndpoint != null) {
                    clientEndpoint.notify(method, parameter);
                }
            }

            // Store the client endpoint when it's set by ServiceEndpoints
            public void setClientEndpoint(org.eclipse.lsp4j.jsonrpc.Endpoint endpoint) {
                this.clientEndpoint = endpoint;
            }
        };

        // Return composite service that implements both LanguageClient and Endpoint
        return new CompositeService(client, delegatingEndpoint);
    }

    /**
     * Composite service that implements both LanguageClient and Endpoint.
     */
    private class CompositeService implements LanguageClient, org.eclipse.lsp4j.jsonrpc.Endpoint {
        private final LanguageClient client;
        private final org.eclipse.lsp4j.jsonrpc.Endpoint customEndpoint;

        CompositeService(LanguageClient client, org.eclipse.lsp4j.jsonrpc.Endpoint customEndpoint) {
            this.client = client;
            this.customEndpoint = customEndpoint;
        }

        // Delegate all LanguageClient methods to the wrapped client
        @Override
        public void telemetryEvent(Object object) {
            client.telemetryEvent(object);
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            client.publishDiagnostics(diagnostics);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            client.showMessage(messageParams);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return client.showMessageRequest(requestParams);
        }

        @Override
        public void logMessage(MessageParams message) {
            client.logMessage(message);
        }

        // Endpoint methods - intercept for bindRequest routing
        @Override
        public CompletableFuture<?> request(String method, Object parameter) {
            return customEndpoint.request(method, parameter);
        }

        @Override
        public void notify(String method, Object parameter) {
            customEndpoint.notify(method, parameter);
        }
    }

    /**
     * Information about a bindRequest routing.
     */
    private static class BindRequestInfo {
        final String targetServerId;
        final String mode; // "executeCommand" or "direct"

        BindRequestInfo(String targetServerId, String mode) {
            this.targetServerId = targetServerId;
            this.mode = mode;
        }
    }

    /**
     * Find which server this request should be routed to based on bindRequest declarations.
     * Returns binding info (target server + mode), or null if not a bindRequest.
     */
    private BindRequestInfo findBindRequestInfo(String requestMethod) {
        LOG.infof("[%s] Looking for bindRequest routing for method: %s", config.getId(), requestMethod);

        // Check our own config's contributes sections
        if (config.getContributes() == null) {
            LOG.warnf("[%s] No contributes section in config", config.getId());
            return null;
        }

        if (config.getContributes().getContributions() == null) {
            LOG.warnf("[%s] contributes.getContributions() is null", config.getId());
            return null;
        }

        LOG.infof("[%s] Found %d contribution targets", config.getId(),
            config.getContributes().getContributions().size());

        // Look through all contributes.{serverId}.bindRequest arrays
        for (Map.Entry<String, com.google.gson.JsonElement> entry : config.getContributes().getContributions().entrySet()) {
            String targetServerId = entry.getKey();
            com.google.gson.JsonElement contrib = entry.getValue();

            if (!contrib.isJsonObject()) {
                continue;
            }

            com.google.gson.JsonObject contribObj = contrib.getAsJsonObject();
            if (!contribObj.has("bindRequest")) {
                continue;
            }

            com.google.gson.JsonElement bindRequestElem = contribObj.get("bindRequest");
            if (!bindRequestElem.isJsonArray()) {
                continue;
            }

            // Check if our requestMethod is in this bindRequest array
            com.google.gson.JsonArray bindRequests = bindRequestElem.getAsJsonArray();
            LOG.infof("[%s] Checking %d bindRequests for target '%s'", config.getId(),
                bindRequests.size(), targetServerId);

            for (com.google.gson.JsonElement req : bindRequests) {
                if (req.isJsonPrimitive()) {
                    String bindMethod = req.getAsString();
                    LOG.debugf("[%s] Comparing '%s' with '%s'", config.getId(), requestMethod, bindMethod);

                    if (bindMethod.equals(requestMethod)) {
                        // Found! Now determine the mode
                        String mode = "executeCommand"; // Default mode
                        if (contribObj.has("bindMode") && contribObj.get("bindMode").isJsonPrimitive()) {
                            mode = contribObj.get("bindMode").getAsString();
                        }
                        LOG.infof("[%s] FOUND bindRequest match! Routing to %s (mode: %s)",
                            config.getId(), targetServerId, mode);
                        return new BindRequestInfo(targetServerId, mode);
                    }
                }
            }
        }

        LOG.warnf("[%s] No bindRequest found for method: %s", config.getId(), requestMethod);
        return null;
    }

    /**
     * Prepare initialization options for this server.
     * Subclasses can override to add server-specific options (e.g., bundles for JDT.LS).
     *
     * @return initialization options object, or null if none
     */
    protected Object prepareInitializationOptions() {
        // Default: use options from config if present
        if (config.getInitializationOptions() != null && !config.getInitializationOptions().isEmpty()) {
            return config.getInitializationOptions();
        }
        return null;
    }

    /**
     * Get trace level from global config file (~/.mcp-languagetools/config.json).
     * Returns "off", "messages", or "verbose" (default).
     */
    private String getTraceLevelFromConfig() {
        try {
            Path configFile = pathManager.getGlobalConfigFile();
            if (!Files.exists(configFile)) {
                return "verbose"; // Default
            }

            String json = Files.readString(configFile);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("servers")) {
                return "verbose";
            }

            com.google.gson.JsonObject servers = root.getAsJsonObject("servers");
            if (!servers.has(config.getId())) {
                return "verbose";
            }

            com.google.gson.JsonObject serverConfig = servers.getAsJsonObject(config.getId());
            if (!serverConfig.has("trace")) {
                return "verbose";
            }

            return serverConfig.get("trace").getAsString();
        } catch (Exception e) {
            LOG.debugf("Failed to read trace level from config: %s", e.getMessage());
            return "verbose"; // Default on error
        }
    }

    /**
     * Check if the language server is enabled.
     * Can be controlled by user configuration.
     *
     * @return true if the server is enabled
     */
    public boolean isEnabled() {
        return clientFeatures.isEnabled();
    }

    /**
     * Check if the server supports a given capability for a file.
     *
     * @param capability the LSP capability to check
     * @param document   the language document
     * @return true if the capability is supported
     */
    public boolean supportsCapability(LspCapability capability, LanguageDocument document) {
        return clientFeatures.supportsCapability(capability, document);
    }

    /**
     * Get the client features for this server.
     *
     * @return the client features
     */
    public LspClientFeatures getClientFeatures() {
        return clientFeatures;
    }
}
