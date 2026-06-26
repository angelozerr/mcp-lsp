package com.redhat.mcp.languagetools.lsp.trace;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Collects LSP trace messages from all LSP servers.
 * Keeps a bounded buffer of recent messages and fires CDI events for real-time streaming.
 */
@ApplicationScoped
@RegisterForReflection
public class LspTraceCollector {

    private static final Logger LOG = Logger.getLogger(LspTraceCollector.class);
    private static final int MAX_TRACE_MESSAGES = 1000;

    private final ConcurrentLinkedDeque<LspTraceMessage> traces = new ConcurrentLinkedDeque<>();

    @Inject
    Event<LspTraceMessage> traceEvent;

    public void addTrace(String workspaceUri, String serverId, String serverName, LspTraceMessage.MessageDirection direction, String jsonContent) {
        LspTraceMessage message = new LspTraceMessage(
            workspaceUri,
            serverId,
            serverName,
            Instant.now(),
            direction,
            jsonContent
        );

        traces.addLast(message);

        // Trim to max size
        while (traces.size() > MAX_TRACE_MESSAGES) {
            traces.pollFirst();
        }

        // Fire CDI event for SSE streaming
        traceEvent.fire(message);

        LOG.debugf("[%s/%s] %s: %s", workspaceUri, serverId, direction, jsonContent.substring(0, Math.min(100, jsonContent.length())));
    }

    public List<LspTraceMessage> getRecentTraces(int limit) {
        return traces.stream()
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public List<LspTraceMessage> getTracesForServer(String serverId, int limit) {
        return traces.stream()
            .filter(t -> t.serverId().equals(serverId))
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public List<LspTraceMessage> getTracesForWorkspaceAndServer(String workspaceUri, String serverId, int limit) {
        return traces.stream()
            .filter(t -> t.workspaceUri().equals(workspaceUri) && t.serverId().equals(serverId))
            .skip(Math.max(0, traces.size() - limit))
            .toList();
    }

    public void clear() {
        traces.clear();
    }
}
