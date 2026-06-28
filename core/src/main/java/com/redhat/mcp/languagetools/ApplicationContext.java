package com.redhat.mcp.languagetools;

/**
 * Application-level context shared across all workspaces and all protocol types (LSP, DAP, etc.).
 * Contains only generic infrastructure services.
 */
public interface ApplicationContext {

    /**
     * Get the path manager for resolving file system paths.
     */
    PathManager getPathManager();
}
