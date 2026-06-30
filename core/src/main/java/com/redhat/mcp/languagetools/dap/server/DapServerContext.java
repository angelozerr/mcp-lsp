package com.redhat.mcp.languagetools.dap.server;

import com.redhat.mcp.languagetools.ApplicationContext;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.WorkspaceContext;
import com.redhat.mcp.languagetools.dap.trace.DapTraceCollector;

import java.nio.file.Path;

/**
 * DAP-specific context object containing dependencies needed to create DAP server instances.
 * Extends WorkspaceContext with DAP-specific methods.
 * This interface provides a stable API - when adding new dependencies, add them here
 * instead of changing all server constructors.
 */
public interface DapServerContext extends WorkspaceContext {

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
     * Get the DAP server installation directory.
     */
    Path getDapServerHome();

    /**
     * Get the trace collector for DAP messages.
     */
    DapTraceCollector getTraceCollector();

    /**
     * Get the session ID for tracing.
     */
    String getSessionId();

    /**
     * Get the session name for tracing.
     */
    String getSessionName();
}
