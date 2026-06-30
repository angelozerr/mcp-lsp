package com.redhat.mcp.languagetools.dap.trace;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Collects DAP trace messages from all debug sessions.
 * Keeps a bounded buffer of recent messages and fires CDI events for real-time streaming.
 */
@ApplicationScoped
@RegisterForReflection
public class DapTraceCollector {

    private static final Logger LOG = Logger.getLogger(DapTraceCollector.class);
    private static final int MAX_TRACE_MESSAGES = 1000;

    private final ConcurrentLinkedDeque<DapTraceMessage> traces = new ConcurrentLinkedDeque<>();

    @Inject
    Event<DapTraceMessage> traceEvent;

    public void addTrace(String workspaceUri, String sessionId, String sessionName, DapTraceMessage.MessageDirection direction, String jsonContent) {
        DapTraceMessage message = new DapTraceMessage(
            workspaceUri,
            sessionId,
            sessionName,
            java.time.Instant.now(),
            direction,
            jsonContent
        );

        traces.addLast(message);

        // Trim to max size
        while (traces.size() > MAX_TRACE_MESSAGES) {
            traces.pollFirst();
        }

        // Fire CDI event for WebSocket streaming
        traceEvent.fire(message);

        LOG.debugf("[%s/%s] %s: %s", workspaceUri, sessionId, direction, jsonContent.substring(0, Math.min(100, jsonContent.length())));
    }

    public List<DapTraceMessage> getRecentTraces(int limit) {
        return traces.stream()
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public List<DapTraceMessage> getTracesForSession(String sessionId, int limit) {
        return traces.stream()
            .filter(t -> t.sessionId().equals(sessionId))
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public List<DapTraceMessage> getTracesForWorkspaceAndSession(String workspaceUri, String sessionId, int limit) {
        return traces.stream()
            .filter(t -> t.workspaceUri().equals(workspaceUri) && t.sessionId().equals(sessionId))
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public void clear() {
        traces.clear();
    }
}
