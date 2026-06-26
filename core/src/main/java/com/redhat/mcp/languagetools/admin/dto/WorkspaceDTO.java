package com.redhat.mcp.languagetools.admin.dto;

import java.net.URI;
import java.util.List;

public record WorkspaceDTO(
    URI rootUri,
    boolean initialized,
    java.util.List<McpClientInfo> mcpClients,
    List<LspServerDTO> lspServers
) {
    public record McpClientInfo(
        String name,
        String connectedAt
    ) {}
}
