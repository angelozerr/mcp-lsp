package com.redhat.mcp.languagetools;

import java.net.URI;
import java.nio.file.Path;

/**
 * Workspace-scoped context shared across all protocol types (LSP, DAP, etc.).
 * Contains runtime context for a specific workspace.
 */
public interface WorkspaceContext {

    /**
     * Get the workspace root URI.
     */
    URI getWorkspaceRoot();

    /**
     * Get the workspace data directory.
     */
    Path getWorkspaceDataDir();
}
