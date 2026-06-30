package com.redhat.mcp.languagetools.admin.dto;

/**
 * Request to create a new DAP session.
 */
public record CreateDapSessionRequest(
    String workspaceUri,
    String dapServerId,
    String sessionName
) {
}
