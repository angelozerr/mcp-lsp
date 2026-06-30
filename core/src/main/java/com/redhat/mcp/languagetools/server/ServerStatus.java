package com.redhat.mcp.languagetools.server;

/**
 * LSP Server status.
 */
public enum ServerStatus {

    NOT_STARTED,

    /**
     * Server is being installed.
     */
    INSTALLING,

    /**
     * Server is being started but not yet initialized.
     */
    STARTING,

    /**
     * Server is running and initialized.
     */
    RUNNING,

    /**
     * Server is being stopped.
     */
    STOPPING,

    /**
     * Server is stopped.
     */
    STOPPED,

    /**
     * Server installation failed (download error, extraction error, etc.).
     */
    INSTALL_FAILED,

    /**
     * Server startup failed (process error, initialization error, etc.).
     */
    START_FAILED,

    /**
     * Server is switching from external (IDE) to MCP-managed, or vice-versa.
     */
    SWITCHING,

    /**
     * Connecting to an external LSP server instance (launched by IDE).
     */
    CONNECTING_TO_IDE,

    /**
     * Connected to an external LSP server instance (launched by IDE).
     */
    CONNECTED_TO_IDE,

    ERROR,
    /**
     * Disconnecting from an external LSP server instance.
     */
    DISCONNECTING
}
