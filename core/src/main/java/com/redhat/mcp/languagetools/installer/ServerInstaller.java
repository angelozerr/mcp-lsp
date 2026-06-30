package com.redhat.mcp.languagetools.installer;

import java.util.concurrent.CompletableFuture;

/**
 * Generic interface for server installers (LSP and DAP).
 *
 * The installer performs three functions:
 * 1. Check if server is installed
 * 2. Install if needed
 * 3. Calculate the final command to execute
 */
public interface ServerInstaller {

    /**
     * Ensures the server is installed and returns the result.
     *
     * This method:
     * - Checks if already installed (using check task from installer.json)
     * - Installs if needed (using run task from installer.json)
     * - Always returns the command to execute (from configureServer in installer.json)
     *
     * @param context Installation context with progress indicator and variables
     * @return InstallResult with install dir, command, and status
     */
    CompletableFuture<InstallResult> ensureInstalled(InstallerContext context);

    /**
     * Gets the current installation status.
     */
    InstallationStatus getStatus();

    /**
     * Stops the installation if in progress.
     */
    void stop();
}
