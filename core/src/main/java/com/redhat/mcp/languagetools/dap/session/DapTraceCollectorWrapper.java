package com.redhat.mcp.languagetools.dap.session;

import com.redhat.mcp.languagetools.dap.trace.DapTraceCollector;
import com.redhat.mcp.languagetools.dap.trace.DapTraceMessage;
import com.redhat.mcp.languagetools.trace.TraceCollector;

/**
 * Wrapper that adapts DapTraceCollector to TraceCollector interface.
 */
class DapTraceCollectorWrapper implements TraceCollector {
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
    public void trace(String message, Level level) {
        // Send as SENT message in DAP trace
        dapTraceCollector.addTrace(
            sessionId,
            serverId,
            serverName,
            DapTraceMessage.MessageDirection.SENT,
            "INSTALL: " + message
        );
    }
}
