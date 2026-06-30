package com.redhat.mcp.languagetools.dap.session;

import com.redhat.mcp.languagetools.ApplicationContext;
import com.redhat.mcp.languagetools.WorkspaceContext;
import com.redhat.mcp.languagetools.dap.server.DapServerConfig;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple DAP debug sessions across workspaces.
 *
 * Responsibilities:
 * - Create/destroy debug sessions
 * - Track active sessions (sessionId -> DapSession)
 * - Route tool calls to the correct session
 * - Match debug adapters to languages/file types
 */
@ApplicationScoped
public class DapSessionManager implements ApplicationContext {

    private static final Logger LOG = Logger.getLogger(DapSessionManager.class);

    @Inject
    ApplicationManager applicationManager;

    @Inject
    com.redhat.mcp.languagetools.PathManager pathManager;

    @Inject
    com.redhat.mcp.languagetools.dap.trace.DapTraceCollector traceCollector;

    private final Map<String, DapSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create a new debug session for a specific DAP server.
     *
     * @param workspaceUri Workspace URI for context
     * @param dapServerId DAP server ID (e.g., "debugpy", "vscode-js-debug")
     * @param sessionName User-friendly name for the session
     * @return Created DapSession
     */
    public DapSession createSession(URI workspaceUri, String dapServerId, String sessionName) {
        LOG.infof("Creating debug session: workspace=%s, dapServerId=%s, name=%s",
            workspaceUri, dapServerId, sessionName);

        // Find workspace
        Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);
        if (workspace == null) {
            throw new IllegalArgumentException("Workspace not found: " + workspaceUri);
        }

        // Find DAP server config
        DapServerConfig serverConfig = findDapServerById(workspace, dapServerId);
        if (serverConfig == null) {
            throw new IllegalArgumentException("DAP server not found: " + dapServerId);
        }

        // Set trace collector for installation support
        String sessionId = UUID.randomUUID().toString();
        if (serverConfig.getTraceCollector() == null) {
            // Create a TraceCollector wrapper around DapTraceCollector
            serverConfig.setTraceCollector(new DapTraceCollectorWrapper(traceCollector, sessionId, dapServerId, serverConfig.getName()));
        }

        // Determine language from server config (first supported language)
        String language = extractLanguageFromConfig(serverConfig);

        // Create session
        WorkspaceContext wsContext = createWorkspaceContext(workspace);

        DapSession session = new DapSession(
            sessionId,
            language,
            sessionName,
            serverConfig,
            this,
            wsContext,
            traceCollector
        );

        sessions.put(sessionId, session);
        LOG.infof("Created session %s for %s (%s)", sessionId, language, sessionName);

        // Don't initialize yet - initialization (including installation) happens on first launch
        return session;
    }

    /**
     * Create a new debug session for a specific language.
     *
     * @param language Language to debug (e.g., "javascript", "python", "go")
     * @param sessionName User-friendly name for the session
     * @param workspaceUri Workspace URI for context
     * @return Session info with sessionId
     */
    public CompletableFuture<Map<String, Object>> createSession(String language,
                                                                  String sessionName,
                                                                  URI workspaceUri) {
        LOG.infof("Creating debug session for language '%s' in workspace %s", language, workspaceUri);

        // Find workspace
        Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);
        if (workspace == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Workspace not found: " + workspaceUri)
            );
        }

        // Find DAP server for this language
        DapServerConfig serverConfig = findDapServerForLanguage(workspace, language);
        if (serverConfig == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No debug adapter found for language: " + language)
            );
        }

        // Create session
        String sessionId = UUID.randomUUID().toString();
        WorkspaceContext wsContext = createWorkspaceContext(workspace);

        DapSession session = new DapSession(
            sessionId,
            language,
            sessionName,
            serverConfig,
            this,
            wsContext,
            traceCollector
        );

        sessions.put(sessionId, session);
        LOG.infof("Created session %s for %s (%s)", sessionId, language, sessionName);

        // Initialize the session
        return session.initialize()
            .thenApply(v -> {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("sessionId", sessionId);
                result.put("language", language);
                result.put("sessionName", sessionName);
                result.put("message", "Created " + language + " debug session: " + sessionName);
                return result;
            })
            .exceptionally(ex -> {
                sessions.remove(sessionId);
                LOG.errorf(ex, "Failed to initialize session %s", sessionId);
                throw new RuntimeException("Failed to create debug session: " + ex.getMessage(), ex);
            });
    }

    /**
     * List all active debug sessions.
     */
    public List<Map<String, Object>> listSessions() {
        LOG.debugf("Listing %d active debug sessions", sessions.size());

        List<Map<String, Object>> result = new ArrayList<>();
        for (DapSession session : sessions.values()) {
            result.add(Map.of(
                "sessionId", session.getSessionId(),
                "language", session.getLanguage(),
                "sessionName", session.getSessionName(),
                "state", session.getState().name()
            ));
        }
        return result;
    }

    /**
     * Get all active sessions.
     */
    public List<DapSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Get a session by ID (returns null if not found).
     */
    public DapSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Remove a session from the manager.
     */
    public void removeSession(String sessionId) {
        DapSession session = sessions.remove(sessionId);
        if (session != null) {
            LOG.infof("Removed session: %s", sessionId);
        }
    }

    /**
     * Close and remove a debug session.
     */
    public CompletableFuture<Map<String, Object>> closeSession(String sessionId) {
        LOG.infof("Closing debug session: %s", sessionId);

        DapSession session = sessions.remove(sessionId);
        if (session == null) {
            return CompletableFuture.completedFuture(Map.of(
                "success", false,
                "message", "Session not found: " + sessionId
            ));
        }

        return session.terminate()
            .thenApply(v -> {
                LOG.infof("Session closed: %s", sessionId);
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("sessionId", sessionId);
                result.put("message", "Debug session closed");
                return result;
            })
            .exceptionally(ex -> {
                LOG.errorf(ex, "Error closing session %s", sessionId);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("sessionId", sessionId);
                result.put("message", "Error closing session: " + ex.getMessage());
                return result;
            });
    }

    /**
     * List supported languages based on available DAP servers.
     */
    public List<String> listSupportedLanguages() {
        Set<String> languages = new HashSet<>();

        Map<String, DapServerConfig> dapConfigs = applicationManager.getDapServerConfigs();
        for (DapServerConfig config : dapConfigs.values()) {
            if (config.getDocumentSelector() != null) {
                config.getDocumentSelector().forEach(selector -> {
                    if (selector.getLanguage() != null) {
                        languages.add(selector.getLanguage());
                    }
                });
            }
        }

        List<String> result = new ArrayList<>(languages);
        Collections.sort(result);
        LOG.debugf("Supported languages: %s", result);
        return result;
    }

    /**
     * Get statistics about active sessions.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Long> stateCount = new HashMap<>();
        for (DapSession session : sessions.values()) {
            String state = session.getState().name();
            stateCount.put(state, stateCount.getOrDefault(state, 0L) + 1);
        }

        return Map.of(
            "totalSessions", sessions.size(),
            "sessionsByState", stateCount,
            "supportedLanguages", listSupportedLanguages().size()
        );
    }

    // ========== Helper Methods ==========

    /**
     * Find DAP server by ID.
     */
    private DapServerConfig findDapServerById(Workspace workspace, String dapServerId) {
        // First check global DAP servers
        Map<String, DapServerConfig> globalDapServers = applicationManager.getDapServerConfigs();
        DapServerConfig config = globalDapServers.get(dapServerId);
        if (config != null) {
            return config;
        }

        // Then check workspace-specific DAP servers
        Map<String, DapServerConfig> workspaceDapServers = workspace.getDapServerConfigs();
        return workspaceDapServers.get(dapServerId);
    }

    /**
     * Extract language from DAP server config (first supported language).
     */
    private String extractLanguageFromConfig(DapServerConfig config) {
        if (config.getDocumentSelector() != null && !config.getDocumentSelector().isEmpty()) {
            String lang = config.getDocumentSelector().get(0).getLanguage();
            if (lang != null) {
                return lang;
            }
        }
        // Fallback: derive from server ID
        return config.getId();
    }

    /**
     * Find the appropriate DAP server for a language.
     */
    private DapServerConfig findDapServerForLanguage(Workspace workspace, String language) {
        // First check workspace-specific DAP servers
        Map<String, DapServerConfig> workspaceDapServers = workspace.getDapServerConfigs();
        for (DapServerConfig config : workspaceDapServers.values()) {
            if (supportsLanguage(config, language)) {
                return config;
            }
        }

        // Fallback to global DAP servers
        Map<String, DapServerConfig> globalDapServers = applicationManager.getDapServerConfigs();
        for (DapServerConfig config : globalDapServers.values()) {
            if (supportsLanguage(config, language)) {
                return config;
            }
        }

        return null;
    }

    /**
     * Check if a DAP server config supports a specific language.
     */
    private boolean supportsLanguage(DapServerConfig config, String language) {
        if (config.getDocumentSelector() == null) {
            return false;
        }

        return config.getDocumentSelector().stream()
            .anyMatch(selector -> language.equalsIgnoreCase(selector.getLanguage()));
    }

    /**
     * Create a WorkspaceContext from a Workspace.
     */
    private WorkspaceContext createWorkspaceContext(Workspace workspace) {
        return new WorkspaceContext() {
            @Override
            public URI getWorkspaceRoot() {
                return workspace.getRootUri();
            }

            @Override
            public java.nio.file.Path getWorkspaceDataDir() {
                return workspace.getWorkspaceDataDir();
            }
        };
    }

    // ========== ApplicationContext Implementation ==========

    @Override
    public com.redhat.mcp.languagetools.PathManager getPathManager() {
        return pathManager;
    }

    // ========== Cleanup ==========

    /**
     * Shutdown all active sessions (called on application shutdown).
     */
    public CompletableFuture<Void> shutdownAll() {
        LOG.infof("Shutting down all %d debug sessions", sessions.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DapSession session : sessions.values()) {
            futures.add(session.terminate());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                sessions.clear();
                LOG.info("All debug sessions shut down");
            });
    }
}
