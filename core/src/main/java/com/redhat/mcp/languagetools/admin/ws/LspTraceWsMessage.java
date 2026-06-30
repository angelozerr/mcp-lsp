package com.redhat.mcp.languagetools.admin.ws;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * WebSocket message for LSP trace events.
 */
@RegisterForReflection
public record LspTraceWsMessage(
    String type,  // "lsp-trace"
    String workspaceUri,
    String serverId,
    String serverName,
    String timestamp,
    String direction,
    String jsonContent,
    String messageType  // "TRACE", "UPDATE", "ERROR", "INFO" - null = TRACE
) {
}
