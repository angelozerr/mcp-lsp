package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.server.ServerStatus;

/**
 * Runtime state of a server in a specific workspace.
 * This represents the dynamic state that changes during execution.
 */
public record ServerRuntimeDTO(
    String serverId,
    ServerStatus status,
    String statusMessage,
    boolean isReady,
    Long pid,
    String command,
    ExternalInstanceInfo externalInstance,
    String parentServerId  // For extensions: the server they extend (null for normal servers)
) {
    /**
     * Information about an external LSP server instance (launched by an IDE).
     */
    public record ExternalInstanceInfo(
        int port,
        long pid,
        boolean isAlive,
        String clientName,
        String clientVersion
    ) {}
}
