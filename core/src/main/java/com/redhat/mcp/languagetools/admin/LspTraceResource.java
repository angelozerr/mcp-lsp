package com.redhat.mcp.languagetools.admin;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST endpoints for LSP trace messages.
 * Provides both historical trace retrieval and real-time SSE streaming.
 */
@ApplicationScoped
@Path("/api/admin/traces")
public class LspTraceResource {

    private static final Logger LOG = Logger.getLogger(LspTraceResource.class);

    @Inject
    LspTraceCollector traceCollector;

    @Inject
    BroadcastProcessor<LspTraceMessage> traceProcessor;

    // Track active SSE connections
    private final Map<String, Boolean> activeConnections = new ConcurrentHashMap<>();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<LspTraceMessage> getRecentTraces(@QueryParam("limit") @DefaultValue("100") int limit) {
        return traceCollector.getRecentTraces(limit);
    }

    @GET
    @Path("/server/{serverId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LspTraceMessage> getTracesForServer(
        @PathParam("serverId") String serverId,
        @QueryParam("limit") @DefaultValue("100") int limit
    ) {
        return traceCollector.getTracesForServer(serverId, limit);
    }

    @GET
    @Path("/workspace/{workspaceUri}/server/{serverId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LspTraceMessage> getTracesForWorkspaceAndServer(
        @PathParam("workspaceUri") String workspaceUri,
        @PathParam("serverId") String serverId,
        @QueryParam("limit") @DefaultValue("100") int limit
    ) {
        return traceCollector.getTracesForWorkspaceAndServer(workspaceUri, serverId, limit);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<LspTraceMessage> streamTraces() {
        String connectionId = java.util.UUID.randomUUID().toString();
        activeConnections.put(connectionId, true);
        LOG.infof("SSE client connected: %s (total: %d)", connectionId, activeConnections.size());

        return SseHelper.withAutoClose(Multi.createFrom().publisher(traceProcessor))
                .onTermination().invoke(() -> {
                    activeConnections.remove(connectionId);
                    LOG.infof("SSE client disconnected: %s (remaining: %d)", connectionId, activeConnections.size());
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    @DELETE
    public void clearTraces() {
        traceCollector.clear();
    }

    public void onTraceEvent(@Observes LspTraceMessage trace) {
        LOG.debugf("Broadcasting trace: %s", trace.serverId());
        try {
            traceProcessor.onNext(trace);
        } catch (Exception e) {
            // Ignore backpressure errors - events will be dropped by onOverflow().drop()
            LOG.debugf("Failed to broadcast trace (backpressure): %s", e.getMessage());
        }
    }
}
