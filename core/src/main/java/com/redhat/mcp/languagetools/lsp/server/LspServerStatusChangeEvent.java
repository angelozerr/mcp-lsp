package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.server.ServerStatus;

import java.net.URI;

/**
 * CDI event fired when an LSP server status changes.
 */
public record LspServerStatusChangeEvent(
    URI workspaceUri,
    String serverId,
    ServerStatus oldStatus,
    ServerStatus newStatus
) {
}
