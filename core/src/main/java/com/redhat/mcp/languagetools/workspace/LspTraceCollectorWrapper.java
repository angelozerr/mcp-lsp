package com.redhat.mcp.languagetools.workspace;

import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;
import com.redhat.mcp.languagetools.trace.TraceCollector;

/**
 * Wrapper that adapts LspTraceCollector to TraceCollector interface.
 */
class LspTraceCollectorWrapper implements TraceCollector {
    private final LspTraceCollector lspTraceCollector;
    private final String workspaceUri;
    private final String serverId;
    private final String serverName;

    public LspTraceCollectorWrapper(LspTraceCollector lspTraceCollector, String workspaceUri, String serverId, String serverName) {
        this.lspTraceCollector = lspTraceCollector;
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
        this.serverName = serverName;
    }

    @Override
    public void trace(String message, Level level) {
        // Send as client->server message in LSP trace
        lspTraceCollector.addTrace(
            workspaceUri,
            serverId,
            serverName,
            LspTraceMessage.MessageDirection.CLIENT_TO_SERVER,
            "INSTALL: " + message
        );
    }
}
