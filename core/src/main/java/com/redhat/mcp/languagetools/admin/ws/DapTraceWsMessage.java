package com.redhat.mcp.languagetools.admin.ws;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * WebSocket message for DAP trace events.
 */
@RegisterForReflection
public record DapTraceWsMessage(
    String type,  // "dap-trace"
    String workspaceUri,
    String sessionId,
    String sessionName,
    String timestamp,
    String direction,
    String jsonContent
) {
}
