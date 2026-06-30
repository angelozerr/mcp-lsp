package com.redhat.mcp.languagetools.lsp.trace;

import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.time.Instant;

/**
 * Represents a captured LSP trace message (request/response/notification).
 * Similar to lsp4ij TracingMessageConsumer format.
 */
public record LspTraceMessage(
    String workspaceUri,
    String serverId,
    String serverName,
    Instant timestamp,
    MessageDirection direction,
    String jsonContent,
    TraceCollector.MessageType messageType  // null = TRACE (default)
) {
    public enum MessageDirection {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }
}
