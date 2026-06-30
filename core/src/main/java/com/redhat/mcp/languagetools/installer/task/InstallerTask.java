package com.redhat.mcp.languagetools.installer.task;

import com.redhat.mcp.languagetools.installer.InstallerContext;

/**
 * Base interface for installer tasks.
 */
public interface InstallerTask {
    /**
     * Executes the task.
     *
     * @param context Installation context
     * @return true if task succeeded, false otherwise
     */
    boolean execute(InstallerContext context);

    /**
     * Gets the task name (for logging).
     */
    String getName();
}
