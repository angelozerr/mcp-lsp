package com.redhat.mcp.languagetools.admin.ws;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * WebSocket message for server status change events.
 */
@RegisterForReflection
public record ServerStatusChangedWsMessage(
    String type,  // "server-status-changed"
    String workspaceUri,
    String serverId,
    String oldStatus,
    String newStatus
) {
}
