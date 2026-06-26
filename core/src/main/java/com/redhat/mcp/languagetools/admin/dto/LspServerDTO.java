package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.lsp.server.ServerStatus;

import java.util.List;

public record LspServerDTO(
    String id,
    String name,
    ServerStatus status,
    String statusMessage,
    boolean isReady,
    List<String> contributesTo,
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
