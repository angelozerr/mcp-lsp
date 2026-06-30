package com.redhat.mcp.languagetools.mcp.trace;

import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.time.Instant;

/**
 * Represents a single MCP traffic trace (request/response).
 */
public record McpTrace(
        String direction,      // "Received" or "Sent"
        String connectionId,   // MCP connection ID
        String message,        // JSON message
        Instant timestamp,
        TraceCollector.MessageType messageType  // null = TRACE (default)
) {
}
