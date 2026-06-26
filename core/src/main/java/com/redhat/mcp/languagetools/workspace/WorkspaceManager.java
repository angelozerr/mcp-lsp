package com.redhat.mcp.languagetools.workspace;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.language.LanguageRegistry;
import com.redhat.mcp.languagetools.lsp.*;
import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;
import com.redhat.mcp.languagetools.lsp.installer.task.InstallerTaskRegistry;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import com.redhat.mcp.languagetools.lsp.server.ServerDescriptorLoader;
import com.redhat.mcp.languagetools.lsp.server.ServerStatus;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import com.redhat.mcp.languagetools.admin.McpClientChangeEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStreamReader;
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
public class WorkspaceManager {

    private static final Logger LOG = Logger.getLogger(WorkspaceManager.class);

    @Inject
    ServerDescriptorLoader serverDescriptorLoader;

    @Inject
    LspTraceCollector traceCollector;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    com.redhat.mcp.languagetools.mcp.McpClientTracker mcpClientTracker;

    @Inject
    Event<WorkspaceChangeEvent> workspaceChangeEvent;

    @Inject
    Event<McpClientChangeEvent> mcpClientChangeEvent;

    @Inject
    Event<LspServerStatusChangeEvent> lspServerStatusChangeEvent;

    @Inject
    PathManager pathManager;

    private final Map<URI, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, LspServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, Path> serverHomes = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        LOG.info("WorkspaceManager starting...");

        // Load all bundled server descriptors
        serverConfigs.putAll(serverDescriptorLoader.loadAllBundled());

        LOG.infof("Loaded %d LSP server descriptors", serverConfigs.size());
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        LOG.info("Shutting down all workspaces...");
        shutdownAll().join();
    }

    /**
     * Create extension manager for collecting server contributions.
     */
    private ExtensionManager createExtensionManager() {
        // Use PathManager for servers directory
        Path baseDir = pathManager.getLspServersDir();
        return new ExtensionManager(serverConfigs, baseDir);
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
            Workspace ws = new Workspace(uri, pathManager.getWorkspaceDataDir(), traceCollector, pathManager);

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

        for (LspServerConfig config : serverConfigs.values()) {
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

                        // No need to install, use a dummy serverHome (won't be used for socket connection)
                        Path dummyHome = pathManager.getServerHome(config.getId());
                        workspace.setExtensionManager(createExtensionManager());
                        workspace.addLspServer(config, dummyHome, new ArrayList<>(serverConfigs.values()));

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

                    // No external instance - need to install and start our own
                    // Set status to INSTALLING
                    workspace.setInstallationStatus(config.getId(), ServerStatus.INSTALLING);

                    // Ensure server is installed before starting (don't fail if one server fails)
                    CompletableFuture<Void> future = ensureServerInstalled(config, workspace.getRootUri())
                            .thenCompose(serverHome -> {
                                if (serverHome == null) {
                                    LOG.errorf("Failed to install %s, cannot start", config.getName());
                                    workspace.setInstallationStatus(config.getId(), ServerStatus.INSTALL_FAILED);
                                    return CompletableFuture.completedFuture(null);
                                }

                                // Clear installation status (will be replaced by server status)
                                workspace.setInstallationStatus(config.getId(), null);

                                // Add server to workspace
                                workspace.setExtensionManager(createExtensionManager());
                                workspace.addLspServer(config, serverHome, new ArrayList<>(serverConfigs.values()));

                                // Start and initialize
                                var server = workspace.getLspServer(config.getId());
                                if (server != null) {
                                    return server.start()
                                            .thenCompose(v -> server.initialize())
                                            .exceptionally(ex -> {
                                                LOG.errorf(ex, "Failed to start %s", config.getName());
                                                workspace.setInstallationStatus(config.getId(), ServerStatus.START_FAILED);
                                                return null;
                                            });
                                }

                                return CompletableFuture.completedFuture(null);
                            })
                            .exceptionally(ex -> {
                                LOG.errorf(ex, "Error during installation of %s", config.getName());
                                workspace.setInstallationStatus(config.getId(), ServerStatus.INSTALL_FAILED);
                                return null;
                            });
                    serverFutures.add(future);
                }
            }
        }

        // Wait for all servers to start (continue even if some fail)
        if (serverFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(serverFutures.toArray(new CompletableFuture[serverFutures.size()]));
    }

    /**
     * Load installer.json from user config directory first, then fallback to bundled resources.
     */
    private JsonObject loadInstallerJson(String serverId, Gson gson) {
        // Try user config directory first
        Path userConfigPath = pathManager.getServerInstallerConfig(serverId);

        try {
            if (Files.exists(userConfigPath)) {
                String content = Files.readString(userConfigPath);
                LOG.infof("Loading installer.json from user config: %s", userConfigPath);
                return gson.fromJson(content, JsonObject.class);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load user installer.json for %s", serverId);
        }

        // Fallback to bundled resources
        String installerPath = "/lsp/" + serverId + "/installer.json";
        try (var is = getClass().getResourceAsStream(installerPath)) {
            if (is != null) {
                LOG.infof("Loading installer.json from bundled resources: %s", installerPath);
                return gson.fromJson(new InputStreamReader(is), JsonObject.class);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load bundled installer.json for %s", serverId);
        }

        return null;
    }

    /**
     * Ensure a server is installed. Returns the installation directory.
     * Thread-safe: multiple concurrent calls for the same server will wait for the same installation.
     */
    private synchronized CompletableFuture<Path> ensureServerInstalled(LspServerConfig config, java.net.URI workspaceUri) {
        // Check if already in cache
        Path cachedHome = serverHomes.get(config.getId());
        if (cachedHome != null) {
            return CompletableFuture.completedFuture(cachedHome);
        }

        // Load installer.json (user config or bundled)
        Gson gson = new Gson();
        JsonObject installerJson = loadInstallerJson(config.getId(), gson);
        if (installerJson == null) {
            LOG.warnf("No installer.json found for %s", config.getId());
            return CompletableFuture.completedFuture(null);
        }

        try {
            // Create context
            InstallerContext context = new InstallerContext();
            context.setProperty("server.id", config.getId());
            context.setProperty("server.home", pathManager.getServerHome(config.getId()).toString());
            context.setTraceCollector(traceCollector, workspaceUri.toString(), config.getId(), config.getName());

            // Create task registry
            InstallerTaskRegistry registry = new InstallerTaskRegistry();

            // Execute check task if present
            if (installerJson.has("check")) {
                JsonObject checkJson = installerJson.getAsJsonObject("check");
                var checkTask = registry.loadTask(checkJson);

                if (checkTask.execute(context)) {
                    // Already installed, get the directory from context or default
                    String homeDir = context.getPropertyAsString("output.dir");
                    if (homeDir == null) {
                        homeDir = pathManager.getServerHome(config.getId()).toString();
                    }
                    Path home = Paths.get(homeDir);
                    serverHomes.put(config.getId(), home);
                    LOG.infof("%s already installed at: %s", config.getName(), home);
                    return CompletableFuture.completedFuture(home);
                }
            }

            // Not installed, execute run task
            if (installerJson.has("run")) {
                LOG.infof("Installing %s...", config.getName());
                JsonObject runJson = installerJson.getAsJsonObject("run");
                var runTask = registry.loadTask(runJson);

                return CompletableFuture.supplyAsync(() -> {
                    try {
                        boolean success = runTask.execute(context);
                        if (success) {
                            String homeDir = context.getPropertyAsString("output.dir");
                            if (homeDir != null) {
                                Path home = Paths.get(homeDir);
                                serverHomes.put(config.getId(), home);
                                LOG.infof("%s installed successfully at: %s", config.getName(), home);
                                return home;
                            }
                        }
                        LOG.errorf("Failed to install %s", config.getName());
                        return null;
                    } catch (Exception e) {
                        LOG.errorf(e, "Exception during installation of %s", config.getName());
                        throw new RuntimeException("Installation failed: " + config.getName(), e);
                    }
                });
            }

            LOG.warnf("No run task in installer.json for %s", config.getId());
            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load/execute installer for %s", config.getId());
            return CompletableFuture.failedFuture(e);
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

    public Map<String, LspServerConfig> getServerConfigs() {
        return Map.copyOf(serverConfigs);
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
        LspServerConfig config = serverConfigs.get(serverId);
        if (config == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown server: " + serverId));
        }

        Workspace workspace = workspaces.get(normalizeUri(workspaceUri));
        if (workspace == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Workspace not found: " + workspaceUri));
        }

        // Set status to INSTALLING
        workspace.setInstallationStatus(serverId, ServerStatus.INSTALLING);

        // Install server
        return ensureServerInstalled(config, workspaceUri)
                .thenCompose(serverHome -> {
                    if (serverHome == null) {
                        LOG.errorf("Failed to install %s, cannot start", config.getName());
                        workspace.setInstallationStatus(serverId, ServerStatus.INSTALL_FAILED);
                        return CompletableFuture.completedFuture(null);
                    }

                    // Clear installation status
                    workspace.setInstallationStatus(serverId, null);

                    // Add server to workspace
                    workspace.setExtensionManager(createExtensionManager());
                    workspace.addLspServer(config, serverHome, new ArrayList<>(serverConfigs.values()));

                    // Start MCP-managed (do not connect to IDE)
                    return workspace.startManagedLspServer(serverId)
                            .exceptionally(ex -> {
                                LOG.errorf(ex, "Failed to start %s", config.getName());
                                workspace.setInstallationStatus(serverId, ServerStatus.START_FAILED);
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Error during installation of %s", config.getName());
                    workspace.setInstallationStatus(serverId, ServerStatus.INSTALL_FAILED);
                    return null;
                });
    }
}

