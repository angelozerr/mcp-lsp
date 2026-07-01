package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.ApplicationContext;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.WorkspaceContext;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;

import java.nio.file.Path;
import java.util.List;

/**
 * LSP-specific context object containing dependencies needed to create LSP server instances.
 * Extends WorkspaceContext with LSP-specific methods.
 * This interface provides a stable API - when adding new dependencies, add them here
 * instead of changing all server constructors.
 */
public interface LspServerContext extends WorkspaceContext {

    /**
     * Get the application-level context (shared across all workspaces).
     */
    ApplicationContext getApplicationContext();

    /**
     * Get the path manager for resolving file system paths.
     * Convenience method that delegates to getApplicationContext().getPathManager().
     */
    PathManager getPathManager();

    /**
     * Get all server configurations (for reading contributes).
     */
    List<LspServerConfig> getAllServerConfigs();

    /**
     * Get the LSP server installation directory.
     */
    Path getLspServerHome();

    /**
     * Get the trace collector for LSP messages.
     */
    LspTraceCollector getTraceCollector();

    /**
     * Find an LSP server in the workspace by ID.
     * Used for bindRequest routing between servers.
     *
     * @param serverId The server ID to find
     * @return The LSP server, or null if not found
     */
    LspServer findLspServerById(String serverId);
}
