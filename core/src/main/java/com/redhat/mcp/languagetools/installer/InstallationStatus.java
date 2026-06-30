package com.redhat.mcp.languagetools.installer;

/**
 * Status of server installation.
 */
public enum InstallationStatus {
    /**
     * Server is not installed yet.
     */
    NOT_INSTALLED,

    /**
     * Installation is in progress.
     */
    INSTALLING,

    /**
     * Server is successfully installed.
     */
    INSTALLED,

    /**
     * Installation was stopped/cancelled.
     */
    STOPPED,

    /**
     * Installation failed.
     */
    FAILED,

    /**
     * Server was already installed (no action taken).
     */
    ALREADY_INSTALLED
}
