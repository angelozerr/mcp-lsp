package com.redhat.mcp.languagetools.mcp.trace;

import java.time.Instant;

/**
 * Represents a single MCP traffic trace (request/response).
 */
public record McpTrace(
        String direction,      // "Received" or "Sent"
        String connectionId,   // MCP connection ID
        String message,        // JSON message
        Instant timestamp
) {
}
