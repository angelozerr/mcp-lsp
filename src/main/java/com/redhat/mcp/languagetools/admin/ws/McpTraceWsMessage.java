package com.redhat.mcp.languagetools.admin.ws;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * WebSocket message for MCP trace events.
 */
@RegisterForReflection
public record McpTraceWsMessage(
    String type,  // "mcp-trace"
    String direction,
    String connectionId,
    String jsonContent,
    String timestamp
) {
}
