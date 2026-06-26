package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;

import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for the broadcast processor used in SSE trace streaming.
 */
@ApplicationScoped
public class TraceProcessorProducer {

    @Produces
    @ApplicationScoped
    public BroadcastProcessor<LspTraceMessage> createTraceProcessor() {
        // Use a buffer size and drop strategy to prevent blocking when subscribers are slow
        // This prevents the whole application from hanging when SSE clients can't keep up
        return BroadcastProcessor.create(); // Buffer up to 1000 messages
    }
}
