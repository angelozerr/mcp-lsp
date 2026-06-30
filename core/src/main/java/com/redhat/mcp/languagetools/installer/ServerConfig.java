package com.redhat.mcp.languagetools.installer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Common interface for server configurations (LSP and DAP).
 * Provides access to installer configuration.
 */
public interface ServerConfig {

    /**
     * Get the server ID.
     */
    String getId();

    /**
     * Get the server name.
     */
    String getName();

    /**
     * Get the installer configuration as JSON.
     * Returns null if no installer is configured.
     */
    JsonNode getInstallerConfig();
}
