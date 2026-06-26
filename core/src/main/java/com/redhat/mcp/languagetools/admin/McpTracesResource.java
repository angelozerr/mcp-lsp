package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.mcp.trace.McpTrace;
import com.redhat.mcp.languagetools.mcp.trace.McpTraceCollector;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * REST API for MCP traffic traces.
 */
@Path("/api/admin/mcp-traces")
@Produces(MediaType.APPLICATION_JSON)
public class McpTracesResource {

    @Inject
    McpTraceCollector traceCollector;

    private final List<io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor<McpTraceDto>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Get all MCP traces.
     */
    @GET
    public List<McpTraceDto> getAllTraces(@QueryParam("limit") @DefaultValue("100") int limit) {
        return traceCollector.getTraces(limit).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Clear all MCP traces.
     */
    @DELETE
    public Response clearTraces() {
        traceCollector.clearTraces();
        return Response.ok().build();
    }

    /**
     * SSE stream for real-time MCP traces.
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<McpTraceDto> streamTraces() {
        var processor = io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor.<McpTraceDto>create();
        subscribers.add(processor);

        return SseHelper.withAutoClose(processor)
                .onCancellation().invoke(() -> subscribers.remove(processor))
                .onTermination().invoke(() -> subscribers.remove(processor));
    }

    /**
     * CDI observer to broadcast traces to SSE subscribers.
     */
    void onMcpTrace(@Observes McpTrace trace) {
        org.jboss.logging.Logger.getLogger(McpTracesResource.class).infof(
            "McpTracesResource received trace event: %s [%s] - broadcasting to %d subscribers",
            trace.direction(), trace.connectionId(), subscribers.size()
        );

        McpTraceDto dto = toDto(trace);
        subscribers.forEach(processor -> {
            try {
                processor.onNext(dto);
                org.jboss.logging.Logger.getLogger(McpTracesResource.class).debugf(
                    "Broadcasted trace to subscriber");
            } catch (Exception e) {
                org.jboss.logging.Logger.getLogger(McpTracesResource.class).errorf(e,
                    "Failed to broadcast trace to subscriber");
            }
        });
    }

    private McpTraceDto toDto(McpTrace trace) {
        return new McpTraceDto(
                trace.direction(),
                trace.connectionId(),
                trace.message(),
                trace.timestamp().toString()
        );
    }

    @RegisterForReflection
    public record McpTraceDto(
            String direction,
            String connectionId,
            String jsonContent,
            String timestamp
    ) {
    }
}
