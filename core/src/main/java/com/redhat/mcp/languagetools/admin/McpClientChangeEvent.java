package com.redhat.mcp.languagetools.admin;

/**
 * CDI event fired when MCP clients connect or disconnect.
 */
public record McpClientChangeEvent() {
    // Simple marker event - we just need to know "something changed"
}
