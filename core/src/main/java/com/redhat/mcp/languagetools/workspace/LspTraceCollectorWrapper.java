package com.redhat.mcp.languagetools.workspace;

import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;
import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Wrapper that adapts LspTraceCollector to TraceCollector interface.
 */
class LspTraceCollectorWrapper implements TraceCollector {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

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
    public void trace(String message, Level level, MessageType type) {
        // Format installation messages like trace messages: [Installation - HH:mm:ss] message
        // BUT: UPDATE messages don't get timestamp to avoid flickering when progress updates
        String formattedMessage;
        if (type == MessageType.UPDATE) {
            // No timestamp prefix for UPDATE - just the message (e.g., "Downloading: 3.2 MB / 45 MB (7%)")
            formattedMessage = message;
        } else {
            // Normal messages get timestamp prefix
            formattedMessage = String.format("[Installation - %s] %s",
                TIME_FORMATTER.format(Instant.now()),
                message);
        }

        // Send as client->server message in LSP trace
        // MessageType will be handled by the trace display (UPDATE = replace last line)
        lspTraceCollector.addTrace(
            workspaceUri,
            serverId,
            serverName,
            LspTraceMessage.MessageDirection.CLIENT_TO_SERVER,
            formattedMessage,
            type
        );
    }
}
