package com.redhat.mcp.languagetools.dap.session;

import com.redhat.mcp.languagetools.dap.trace.DapTraceCollector;
import com.redhat.mcp.languagetools.dap.trace.DapTraceMessage;
import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Wrapper that adapts DapTraceCollector to TraceCollector interface.
 */
class DapTraceCollectorWrapper implements TraceCollector {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DapTraceCollector dapTraceCollector;
    private final String sessionId;
    private final String serverId;
    private final String serverName;

    public DapTraceCollectorWrapper(DapTraceCollector dapTraceCollector, String sessionId, String serverId, String serverName) {
        this.dapTraceCollector = dapTraceCollector;
        this.sessionId = sessionId;
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

        // Send as SENT message in DAP trace
        // MessageType will be handled by the trace display (UPDATE = replace last line)
        dapTraceCollector.addTrace(
            sessionId,
            serverId,
            serverName,
            DapTraceMessage.MessageDirection.SENT,
            formattedMessage,
            type
        );
    }
}
