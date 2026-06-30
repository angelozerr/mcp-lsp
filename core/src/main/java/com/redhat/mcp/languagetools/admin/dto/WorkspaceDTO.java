package com.redhat.mcp.languagetools.admin.dto;

import java.net.URI;
import java.util.List;

public record WorkspaceDTO(
    URI rootUri,
    boolean initialized,
    List<McpClientInfo> mcpClients,
    List<ServerRuntimeDTO> lspServers,
    List<DapServerDTO> dapServers,
    List<DapSessionDTO> dapSessions
) {
    public record McpClientInfo(
        String name,
        String connectedAt
    ) {}

    public record DapSessionDTO(
        String sessionId,
        String sessionName,
        String dapServerId,
        String state,
        String language
    ) {}
}
