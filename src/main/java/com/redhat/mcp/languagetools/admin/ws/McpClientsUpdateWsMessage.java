package com.redhat.mcp.languagetools.admin.ws;

import com.redhat.mcp.languagetools.admin.dto.McpClientDTO;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * WebSocket message for full MCP clients list updates.
 */
@RegisterForReflection
public record McpClientsUpdateWsMessage(
    String type,  // "mcp-clients-update"
    List<McpClientDTO> clients
) {
}
