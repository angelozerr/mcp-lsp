package com.redhat.mcp.languagetools.settings;

/**
 * Server trace level (like VS Code trace setting).
 * Controls verbosity of server communication logging.
 *
 * Values are stored in lowercase for LSP/DAP/MCP protocol compatibility.
 */
public enum ServerTrace {
    /**
     * No tracing.
     */
    off,

    /**
     * Trace messages only.
     */
    messages,

    /**
     * Verbose tracing (messages + detailed info).
     */
    verbose;

    /**
     * Parse from string value (case-insensitive).
     */
    public static ServerTrace fromValue(String value) {
        if (value == null) {
            return off;
        }
        try {
            return valueOf(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return off;
        }
    }

    @Override
    public String toString() {
        return name(); // Already lowercase
    }
}
