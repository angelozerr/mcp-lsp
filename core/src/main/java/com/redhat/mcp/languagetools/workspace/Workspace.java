package com.redhat.mcp.languagetools.workspace;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.ExtensionManager;
import com.redhat.mcp.languagetools.lsp.LspInstanceRegistry;
import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.LspServerContext;
import com.redhat.mcp.languagetools.lsp.server.LspServerFactoryRegistry;
import com.redhat.mcp.languagetools.lsp.RequestRouter;
import com.redhat.mcp.languagetools.lsp.server.ServerStatus;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a workspace (project) with its language server instances.
 * A workspace can have multiple LSP servers (e.g., JDT.LS + Qute LS).
 */
public class Workspace {

    private static final Logger LOG = Logger.getLogger(Workspace.class);

    private final URI rootUri;
    private final Path workspaceDataDir;
    private final LspTraceCollector traceCollector;
    private final PathManager pathManager;
    private final WorkspaceConfiguration configuration;
    private ExtensionManager extensionManager;
    private final Map<String, LspServer> lspServers = new ConcurrentHashMap<>();
    private final Map<String, ServerInfo> serverInfos = new ConcurrentHashMap<>();
    private final Map<String, ServerStatus> installationStatus = new ConcurrentHashMap<>();
    private List<LspServerConfig> allServerConfigs = new ArrayList<>();
    private final Map<String, McpClientInfo> mcpClientConnections = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private java.util.function.Consumer<LspServerStatusChangeEvent> statusChangeCallback;

    private static class ServerInfo {
        final LspServerConfig config;
        final Path serverHome;

        ServerInfo(LspServerConfig config, Path serverHome) {
            this.config = config;
            this.serverHome = serverHome;
        }
    }

    public record McpClientInfo(
        String connectionId,
        String name,
        java.time.Instant connectedAt
    ) {
    }

    public Workspace(URI rootUri, Path workspaceDataDir, LspTraceCollector traceCollector,
                     PathManager pathManager) {
        this.rootUri = rootUri;
        this.workspaceDataDir = createWorkspaceDataDir(workspaceDataDir, rootUri);
        this.traceCollector = traceCollector;
        this.pathManager = pathManager;
        this.configuration = new WorkspaceConfiguration(java.nio.file.Paths.get(rootUri));
    }

    /**
     * Set extension manager for this workspace.
     */
    public void setExtensionManager(ExtensionManager extensionManager) {
        this.extensionManager = extensionManager;
    }

    /**
     * Set callback for LSP server status changes.
     */
    public void setServerStatusChangeCallback(java.util.function.Consumer<LspServerStatusChangeEvent> callback) {
        this.statusChangeCallback = callback;
    }

    /**
     * Add a language server to this workspace.
     *
     * @param config Server configuration
     * @param serverHome Server installation directory
     * @param allServerConfigs All server configurations (for reading contributes)
     */
    public void addLspServer(LspServerConfig config, Path serverHome, List<LspServerConfig> allServerConfigs) {
        // Store allServerConfigs for later use (reconnect, etc.)
        this.allServerConfigs = allServerConfigs;

        LspServerContext context =
            LspServerFactoryRegistry.createContext(
                pathManager, allServerConfigs, rootUri, workspaceDataDir, serverHome, traceCollector);

        LspServer server = LspServerFactoryRegistry.createServer(config, context);
        server.setWorkspaceConfiguration(configuration);
        if (extensionManager != null) {
            server.setExtensionManager(extensionManager);
        }

        // Set up request router for bindRequest support
        server.setRequestRouter(createRequestRouter());

        // Register status change callback
        server.setStatusChangeCallback(newStatus -> {
            if (statusChangeCallback != null) {
                statusChangeCallback.accept(new LspServerStatusChangeEvent(
                    rootUri,
                    config.getId(),
                    server.getStatus(),  // oldStatus (we don't track it here, using current as approximation)
                    newStatus
                ));
            }
        });

        lspServers.put(config.getId(), server);
        serverInfos.put(config.getId(), new ServerInfo(config, serverHome));
        LOG.infof("Added LSP server '%s' to workspace: %s", config.getId(), rootUri);
    }

    /**
     * Create a request router that routes bindRequest calls between servers.
     */
    private RequestRouter createRequestRouter() {
        return (targetServerId, method, params, mode) -> {
            // Look up the target server
            LspServer targetServer = lspServers.get(targetServerId);

            if (targetServer == null) {
                LOG.warnf("Target server '%s' not found for bindRequest: %s", targetServerId, method);
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Target server not found: " + targetServerId)
                );
            }

            // Wait for target server to be ready before routing (important for JDT.LS)
            LOG.debugf("Routing request %s to server %s (mode: %s), waiting for server to be ready...",
                method, targetServerId, mode);

            return targetServer.waitUntilReady(30000) // 30 seconds timeout
                .thenCompose(v -> {
                    LOG.debugf("Server %s is ready, routing request %s", targetServerId, method);

                    if ("direct".equals(mode)) {
                        // Direct JSON-RPC request
                        return targetServer.sendRequest(method, params);
                    } else {
                        // Default: workspace/executeCommand (for JDT.LS delegate handlers)
                        return targetServer.sendCommandRequest(method, params);
                    }
                });
        };
    }

    /**
     * Restart a specific LSP server (shutdown old, create new, start).
     * Will try to connect to IDE instance if available.
     */
    public CompletableFuture<Void> restartLspServer(String serverId) {
        ServerInfo info = serverInfos.get(serverId);
        if (info == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server not found: " + serverId));
        }

        LspServer oldServer = lspServers.get(serverId);

        return CompletableFuture.runAsync(() -> {
            try {
                // Shutdown old server if it exists and is not already stopped
                if (oldServer != null && oldServer.getStatus() != ServerStatus.STOPPED) {
                    oldServer.shutdown().join();
                }

                // Create new server instance using factory
                LspServerContext newContext = LspServerFactoryRegistry.createContext(
                    pathManager, allServerConfigs, rootUri, workspaceDataDir, info.serverHome, traceCollector);
                LspServer newServer = LspServerFactoryRegistry.createServer(info.config, newContext);
                newServer.setWorkspaceConfiguration(configuration);

                // Register status change callback
                newServer.setStatusChangeCallback(newStatus -> {
                    if (statusChangeCallback != null) {
                        statusChangeCallback.accept(new LspServerStatusChangeEvent(
                            rootUri,
                            info.config.getId(),
                            newServer.getStatus(),
                            newStatus
                        ));
                    }
                });

                lspServers.put(serverId, newServer);

                // Start and initialize (will detect IDE instance)
                newServer.start()
                        .thenCompose(v -> newServer.initialize())
                        .join();

                LOG.infof("Restarted LSP server '%s' for workspace: %s", serverId, rootUri);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to restart LSP server '%s'", serverId);
                throw new RuntimeException("Failed to restart server: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Start a MCP-managed LSP server only (do not connect to IDE instance).
     */
    public CompletableFuture<Void> startManagedLspServer(String serverId) {
        ServerInfo info = serverInfos.get(serverId);
        if (info == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server not found: " + serverId));
        }

        LspServer oldServer = lspServers.get(serverId);

        return CompletableFuture.runAsync(() -> {
            try {
                // Shutdown old server if it exists and is not already stopped
                if (oldServer != null && oldServer.getStatus() != ServerStatus.STOPPED) {
                    oldServer.shutdown().join();
                }

                // Create new server instance using factory
                LspServerContext newContext = LspServerFactoryRegistry.createContext(
                    pathManager, allServerConfigs, rootUri, workspaceDataDir, info.serverHome, traceCollector);
                LspServer newServer = LspServerFactoryRegistry.createServer(info.config, newContext);
                newServer.setWorkspaceConfiguration(configuration);

                // Register status change callback
                newServer.setStatusChangeCallback(newStatus -> {
                    if (statusChangeCallback != null) {
                        statusChangeCallback.accept(new LspServerStatusChangeEvent(
                            rootUri,
                            info.config.getId(),
                            newServer.getStatus(),
                            newStatus
                        ));
                    }
                });

                lspServers.put(serverId, newServer);

                // Start MCP-managed only (skip IDE instance detection)
                newServer.startManagedOnly()
                        .thenCompose(v -> newServer.initialize())
                        .join();

                LOG.infof("Started MCP-managed LSP server '%s' for workspace: %s", serverId, rootUri);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to start MCP-managed LSP server '%s'", serverId);
                throw new RuntimeException("Failed to start managed server: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Initialize the workspace (start all LSP servers).
     */
    public CompletableFuture<Void> initialize() {
        if (initialized) {
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Initializing workspace: %s", rootUri);

        // Start all servers in parallel
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (LspServer server : lspServers.values()) {
            CompletableFuture<Void> future = server.start()
                    .thenCompose(v -> server.initialize());
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    initialized = true;
                    LOG.infof("Workspace initialized: %s", rootUri);
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to initialize workspace: %s", rootUri);
                    throw new RuntimeException("Failed to initialize workspace: " + rootUri, ex);
                });
    }

    /**
     * Shutdown the workspace (stop all LSP servers).
     */
    public CompletableFuture<Void> shutdown() {
        LOG.infof("Shutting down workspace: %s", rootUri);
        initialized = false;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (LspServer server : lspServers.values()) {
            futures.add(server.shutdown());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    lspServers.clear();
                    LOG.infof("Workspace shut down: %s", rootUri);
                });
    }

    /**
     * Get a language server by ID.
     */
    public LspServer getLspServer(String id) {
        return lspServers.get(id);
    }

    /**
     * Set installation status for a server.
     */
    public void setInstallationStatus(String serverId, ServerStatus status) {
        if (status == null) {
            installationStatus.remove(serverId);
        } else {
            installationStatus.put(serverId, status);
        }
    }

    /**
     * Get status for a server (running server or installation status).
     */
    public ServerStatus getServerStatus(String serverId) {
        // Check if server is running
        LspServer server = lspServers.get(serverId);
        if (server != null) {
            return server.getStatus();
        }

        // Check installation status
        ServerStatus installStatus = installationStatus.get(serverId);
        if (installStatus != null) {
            return installStatus;
        }

        // Default: stopped
        return ServerStatus.STOPPED;
    }

    /**
     * Get all language servers.
     */
    public Map<String, LspServer> getAllLspServers() {
        return Map.copyOf(lspServers);
    }

    /**
     * Get external instance info for a server (launched by an IDE).
     */
    public LspInstanceRegistry.InstanceInfo getExternalInstance(String serverId) {
        try {
            String workspacePath = Paths.get(rootUri).toString();
            return LspInstanceRegistry.findInstance(workspacePath, serverId);
        } catch (Exception e) {
            LOG.debugf("Failed to check for external instance of %s: %s", serverId, e.getMessage());
            return null;
        }
    }

    public URI getRootUri() {
        return rootUri;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get workspace configuration (reads from .vscode/settings.json).
     */
    public WorkspaceConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Add an MCP client name to this workspace.
     */
    /**
     * Add an MCP client to this workspace.
     * @param connectionId MCP connection ID
     * @param clientName Client name (e.g., "claude-code 2.1.183")
     * @return true if this is a new client, false if it already existed
     */
    public boolean addMcpClient(String connectionId, String clientName) {
        if (connectionId != null && !connectionId.isEmpty()) {
            boolean isNew = !mcpClientConnections.containsKey(connectionId);
            if (isNew) {
                mcpClientConnections.put(connectionId, new McpClientInfo(
                    connectionId,
                    clientName,
                    java.time.Instant.now()
                ));
                LOG.infof("Added MCP client '%s' [%s] to workspace: %s (total: %d)",
                         clientName, connectionId, rootUri, mcpClientConnections.size());
            } else {
                LOG.debugf("MCP client '%s' [%s] already connected to workspace: %s",
                          clientName, connectionId, rootUri);
            }
            return isNew;
        }
        return false;
    }

    /**
     * Get all MCP client connections.
     */
    public Map<String, McpClientInfo> getMcpClientConnections() {
        return java.util.Collections.unmodifiableMap(mcpClientConnections);
    }

    private Path createWorkspaceDataDir(Path baseDir, URI rootUri) {
        try {
            String workspaceName = Path.of(rootUri).getFileName().toString();
            Path dir = baseDir.resolve(workspaceName + "-" + Math.abs(rootUri.hashCode()));
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create workspace data directory", e);
        }
    }
}

