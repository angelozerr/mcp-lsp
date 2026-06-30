package com.redhat.mcp.languagetools;

import com.redhat.mcp.languagetools.dap.server.DapServerConfig;
import com.redhat.mcp.languagetools.language.LanguageRegistry;
import com.redhat.mcp.languagetools.lsp.LspContributionManager;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import com.redhat.mcp.languagetools.lsp.server.ServerDescriptorLoader;
import com.redhat.mcp.languagetools.mcp.McpClientTracker;
import com.redhat.mcp.languagetools.server.ServerStatus;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;

import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.workspace.WorkspaceChangeEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import com.redhat.mcp.languagetools.admin.McpClientChangeEvent;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple workspaces, each with its own language server instances.
 * Workspaces are created dynamically on-demand.
 */
@ApplicationScoped
public class ApplicationManager {

    private static final Logger LOG = Logger.getLogger(ApplicationManager.class);

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    PathManager pathManager;

    @Inject
    Event<WorkspaceChangeEvent> workspaceChangeEvent;

    @Inject
    ServerDescriptorLoader serverDescriptorLoader;

    // ----------- LSP servers

    @Inject
    LspContributionManager lspContributionManager;

    @Inject
    LspTraceCollector lspTraceCollector;

    @Inject
    Event<LspServerStatusChangeEvent> lspServerStatusChangeEvent;

    // ----------- MCP servers

    @Inject
    McpClientTracker mcpClientTracker;

    @Inject
    Event<McpClientChangeEvent> mcpClientChangeEvent;

    private final Map<URI, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, LspServerConfig> lspServerConfigs = new ConcurrentHashMap<>();
    private final Map<String, DapServerConfig> dapServerConfigs = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        LOG.info("WorkspaceManager starting...");

        // Load all bundled LSP server descriptors
        lspServerConfigs.putAll(serverDescriptorLoader.loadAllBundled());

        // Load all bundled DAP server descriptors
        dapServerConfigs.putAll(serverDescriptorLoader.loadAllDapBundled());

        // Initialize contribution manager with loaded configs
        initializeLspContributionManager();

        LOG.infof("Loaded %d LSP server descriptors", lspServerConfigs.size());
        LOG.infof("Loaded %d DAP server descriptors", dapServerConfigs.size());
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        LOG.info("Shutting down all workspaces...");
        shutdownAll().join();
    }

    /**
     * Initialize the LspContributionManager with current server configs.
     */
    private void initializeLspContributionManager() {
        lspContributionManager.initialize(lspServerConfigs);
    }

    /**
     * Get or create a workspace for the given root URI.
     * Workspace is created but NOT initialized (servers added on-demand).
     */
    public Workspace getOrCreateWorkspace(URI rootUri) {
        // Normalize URI
        URI normalizedUri = normalizeUri(rootUri);

        // Get or create workspace (without initialization)
        boolean isNewWorkspace = !workspaces.containsKey(normalizedUri);

        Workspace workspace = workspaces.computeIfAbsent(normalizedUri, uri -> {
            Workspace ws = new Workspace(uri, pathManager.getWorkspaceDataDir(), lspTraceCollector, pathManager);

            // Register callback for LSP server status changes
            ws.setServerStatusChangeCallback(event -> {
                LOG.infof("WorkspaceManager: Firing LSP server status change event: %s/%s - %s -> %s",
                        event.workspaceUri(), event.serverId(), event.oldStatus(), event.newStatus());
                lspServerStatusChangeEvent.fire(event);
            });

            LOG.infof("Created workspace %s", uri);
            return ws;
        });

        // Fire event if workspace was just created
        if (isNewWorkspace) {
            workspaceChangeEvent.fire(new WorkspaceChangeEvent(
                WorkspaceChangeEvent.Type.CREATED,
                normalizedUri
            ));
        }

        // Add current MCP client to this workspace
        String clientName = mcpClientTracker.getCurrentClientName();
        String connectionId = mcpClientTracker.getCurrentConnectionId();

        LOG.infof("Adding MCP client to workspace %s: name=%s, connectionId=%s",
                 normalizedUri, clientName, connectionId);

        boolean isNewClient = workspace.addMcpClient(connectionId, clientName);

        // Fire event if a new client was added
        if (isNewClient) {
            LOG.infof("New MCP client added to workspace, firing McpClientChangeEvent");
            mcpClientChangeEvent.fire(new McpClientChangeEvent());
        } else {
            LOG.infof("MCP client already exists in workspace, no event fired");
        }

        return workspace;
    }

    /**
     * Ensure LSP server is running for the given file in the workspace.
     * Detects language, finds matching servers, installs if needed, and starts them.
     */
    private CompletableFuture<Void> ensureServerForFile(Workspace workspace, URI fileUri) {
        // Detect language from file
        Optional<String> languageId = languageRegistry.detectLanguage(fileUri);
        if (languageId.isEmpty()) {
            LOG.debugf("No language detected for: %s", fileUri);
            return CompletableFuture.completedFuture(null);
        }

        String language = languageId.get();
        LOG.debugf("Detected language '%s' for: %s", language, fileUri);

        // Find ALL servers that can handle this language (may be multiple, e.g., JDT.LS + MicroProfile LS for Java)
        List<CompletableFuture<Void>> serverFutures = new ArrayList<>();

        for (LspServerConfig config : lspServerConfigs.values()) {
            // Skip contribution-only configs (they don't run as servers)
            if (config.isContributionOnly()) {
                continue;
            }

            if (config.canHandle(fileUri.toString(), language)) {
                // Check if server already exists in workspace
                if (workspace.getLspServer(config.getId()) == null) {
                    LOG.infof("Need %s for language '%s' in workspace: %s",
                            config.getName(), language, workspace.getRootUri());

                    // Check if there's an external instance (launched by IDE) first
                    var externalInstance = workspace.getExternalInstance(config.getId());
                    if (externalInstance != null) {
                        LOG.infof("Found external %s instance (port %d, PID %d), skipping installation",
                                 config.getName(), externalInstance.port, externalInstance.pid);

                        // No need to install (external instance), add server to workspace
                        workspace.setLspContributionManager(lspContributionManager);
                        workspace.addLspServer(config, new ArrayList<>(lspServerConfigs.values()));

                        // Start and initialize (will connect to socket)
                        var server = workspace.getLspServer(config.getId());
                        if (server != null) {
                            CompletableFuture<Void> future = server.start()
                                    .thenCompose(v -> server.initialize())
                                    .exceptionally(ex -> {
                                        LOG.errorf(ex, "Failed to connect to external %s", config.getName());
                                        workspace.setInstallationStatus(config.getId(), ServerStatus.START_FAILED);
                                        return null;
                                    });
                            serverFutures.add(future);
                        }
                        continue;
                    }

                    // No external instance - need to start our own (installation is handled by server)
                    // Add server to workspace if not already present
                    workspace.setLspContributionManager(lspContributionManager);
                    if (workspace.getLspServer(config.getId()) == null) {
                        workspace.addLspServer(config, new ArrayList<>(lspServerConfigs.values()));
                    }

                    // Start managed server (handles installation automatically)
                    CompletableFuture<Void> future = workspace.startManagedLspServer(config.getId())
                            .exceptionally(ex -> {
                                LOG.errorf(ex, "Failed to start %s", config.getName());
                                return null;
                            });
                    serverFutures.add(future);
                }
            }
        }

        // Also find and add matching DAP servers (without starting them)
        ensureDapServersForFile(workspace, fileUri, language);

        // Wait for all servers to start (continue even if some fail)
        if (serverFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(serverFutures.toArray(new CompletableFuture[serverFutures.size()]));
    }

    /**
     * Find and add matching DAP servers to the workspace for the given file.
     * DAP servers are NOT started - they are added to the workspace configuration only.
     */
    private void ensureDapServersForFile(Workspace workspace, URI fileUri, String language) {
        for (DapServerConfig config : dapServerConfigs.values()) {
            // Check if this DAP server can handle the file's language
            if (config.getDocumentSelector() != null) {
                boolean canHandle = config.getDocumentSelector().stream()
                    .anyMatch(selector -> {
                        if (selector.getLanguage() != null && selector.getLanguage().equals(language)) {
                            return true;
                        }
                        if (selector.getPattern() != null) {
                            // Simple pattern matching (could be improved with glob matching)
                            String path = fileUri.getPath();
                            String pattern = selector.getPattern();
                            // Convert glob to simple contains check for now
                            if (pattern.contains("*")) {
                                String ext = pattern.substring(pattern.lastIndexOf('.'));
                                return path.endsWith(ext.replace("*", "").replace("}", ""));
                            }
                        }
                        return false;
                    });

                if (canHandle && workspace.getDapServerConfig(config.getId()) == null) {
                    workspace.addDapServer(config);
                    LOG.infof("Added DAP server %s for language '%s' in workspace: %s",
                        config.getName(), language, workspace.getRootUri());
                }
            }
        }
    }


    /**
     * Get workspace from a file URI by detecting the workspace root.
     * Walks up the directory tree to find pom.xml or build.gradle.
     * Ensures the appropriate LSP server is started for the file.
     */
    public CompletableFuture<Workspace> getWorkspaceForFile(URI fileUri) {
        URI rootUri = detectWorkspaceRoot(fileUri);
        if (rootUri == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Could not detect workspace root for: " + fileUri)
            );
        }

        Workspace workspace = getOrCreateWorkspace(rootUri);
        return ensureServerForFile(workspace, fileUri)
                .thenApply(v -> workspace);
    }

    /**
     * Get or create workspace from a path (String cwd).
     * Converts the path to URI and creates/returns the workspace.
     *
     * @param cwd the workspace root path (e.g., "/home/user/project" or "C:\\Users\\project")
     * @return completable future with the workspace
     */
    public CompletableFuture<Workspace> getWorkspaceForPath(String cwd) {
        // Convert path to URI
        // If already a URI (starts with file:), use as-is
        // Otherwise convert path to file:/// URI (3 slashes for absolute paths)
        String workspaceUriStr;
        if (cwd.startsWith("file:")) {
            workspaceUriStr = cwd;
        } else {
            // Normalize path separators and create file URI
            String normalizedPath = cwd.replace("\\", "/");
            // Add leading slash if not present (for absolute paths)
            if (!normalizedPath.startsWith("/")) {
                normalizedPath = "/" + normalizedPath;
            }
            workspaceUriStr = "file://" + normalizedPath;
        }

        URI workspaceUri = URI.create(workspaceUriStr);

        // Create workspace directly (no file detection needed)
        Workspace workspace = getOrCreateWorkspace(workspaceUri);
        return CompletableFuture.completedFuture(workspace);
    }

    /**
     * Shutdown all workspaces.
     */
    public CompletableFuture<Void> shutdownAll() {
        LOG.info("Shutting down all workspaces");

        CompletableFuture<?>[] shutdownFutures = workspaces.values().stream()
                .map(Workspace::shutdown)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(shutdownFutures)
                .thenRun(() -> {
                    workspaces.clear();
                    LOG.info("All workspaces shut down");
                });
    }

    /**
     * Detect workspace root by walking up to find pom.xml or build.gradle.
     */
    private URI detectWorkspaceRoot(URI fileUri) {
        try {
            Path path = Paths.get(fileUri);

            // If it's a file, start from its parent directory
            if (Files.isRegularFile(path)) {
                path = path.getParent();
            }

            // Walk up to find pom.xml or build.gradle
            while (path != null) {
                if (Files.exists(path.resolve("pom.xml")) ||
                        Files.exists(path.resolve("build.gradle")) ||
                        Files.exists(path.resolve("build.gradle.kts"))) {
                    return path.toUri();
                }
                path = path.getParent();
            }

            LOG.warnf("Could not find workspace root for: %s", fileUri);
            return null;

        } catch (Exception e) {
            LOG.error("Error detecting workspace root for: " + fileUri, e);
            return null;
        }
    }

    /**
     * Normalize URI (remove trailing slashes).
     */
    private URI normalizeUri(URI uri) {
        String uriStr = uri.toString();
        if (uriStr.endsWith("/")) {
            uriStr = uriStr.substring(0, uriStr.length() - 1);
        }
        return URI.create(uriStr);
    }

    /**
     * Get all active workspaces.
     */
    public Map<URI, Workspace> getWorkspaces() {
        return Map.copyOf(workspaces);
    }

    public Map<String, LspServerConfig> getLspServerConfigs() {
        return Map.copyOf(lspServerConfigs);
    }

    /**
     * Get all DAP server configurations (debug adapters).
     * Used by Qute DAP to discover available debug adapters.
     */
    public Map<String, DapServerConfig> getDapServerConfigs() {
        return Map.copyOf(dapServerConfigs);
    }

    /**
     * Close a workspace: shutdown all its LSP servers and remove it from memory.
     */
    public CompletableFuture<Void> closeWorkspace(URI workspaceUri) {
        Workspace workspace = workspaces.get(workspaceUri);
        if (workspace == null) {
            LOG.warnf("Workspace not found: %s", workspaceUri);
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Closing workspace: %s", workspaceUri);

        // Shutdown all servers in this workspace
        return workspace.shutdown().thenRun(() -> {
            // Remove from active workspaces
            workspaces.remove(workspaceUri);
            LOG.infof("Workspace closed and removed from memory: %s", workspaceUri);

            // Fire workspace closed event
            workspaceChangeEvent.fire(new WorkspaceChangeEvent(
                WorkspaceChangeEvent.Type.CLOSED,
                workspaceUri
            ));
        });
    }

    /**
     * Ensure a server is installed and added to the workspace, then start it.
     */
    public CompletableFuture<Void> ensureServerInstalled(String serverId, URI workspaceUri) {
        LspServerConfig config = lspServerConfigs.get(serverId);
        if (config == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown server: " + serverId));
        }

        Workspace workspace = workspaces.get(normalizeUri(workspaceUri));
        if (workspace == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Workspace not found: " + workspaceUri));
        }

        // Add server to workspace if not already present
        workspace.setLspContributionManager(lspContributionManager);
        if (workspace.getLspServer(serverId) == null) {
            workspace.addLspServer(config, new ArrayList<>(lspServerConfigs.values()));
        }

        // Start managed server (handles installation automatically)
        return workspace.startManagedLspServer(serverId)
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to start %s", config.getName());
                    return null;
                });
    }
}

