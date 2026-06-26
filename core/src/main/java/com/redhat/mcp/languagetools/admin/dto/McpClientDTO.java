package com.redhat.mcp.languagetools.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record McpClientDTO(
        String id,              // connectionId
        String name,            // client name (e.g., "claude-code")
        String version,         // client version
        String protocolVersion, // MCP protocol version
        String connectedAt      // ISO timestamp
) {
}
