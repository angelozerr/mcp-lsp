package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.lsp.ServerStatus;

public record LspServerDTO(
    String id,
    String name,
    ServerStatus status,
    ExternalInstanceInfo externalInstance,
    Long pid,
    String command
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
