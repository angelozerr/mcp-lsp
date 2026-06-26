package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Context object containing dependencies needed to create LSP server instances.
 * This interface isolates the factory API from internal implementation details.
 */
public interface LspServerContext {

    /**
     * Get the path manager for resolving file system paths.
     */
    PathManager getPathManager();

    /**
     * Get all server configurations (for reading contributes).
     */
    List<LspServerConfig> getAllServerConfigs();

    /**
     * Get the workspace root URI.
     */
    URI getWorkspaceRoot();

    /**
     * Get the workspace data directory.
     */
    Path getWorkspaceDataDir();

    /**
     * Get the server installation directory.
     */
    Path getServerHome();

    /**
     * Get the trace collector for LSP messages.
     */
    LspTraceCollector getTraceCollector();
}
