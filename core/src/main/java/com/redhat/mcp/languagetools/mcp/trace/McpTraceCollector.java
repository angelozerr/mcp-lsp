package com.redhat.mcp.languagetools.mcp.trace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects MCP traffic traces from the Quarkus MCP server traffic logger.
 */
@ApplicationScoped
public class McpTraceCollector {

    private final List<McpTrace> traces = new CopyOnWriteArrayList<>();
    private static final int MAX_TRACES = 500;

    // Track pending requests: requestId -> PendingRequest
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    @Inject
    Event<McpTrace> traceEvent;

    private static class PendingRequest {
        final String method;
        final Instant timestamp;
        final String connectionId;

        PendingRequest(String method, Instant timestamp, String connectionId) {
            this.method = method;
            this.timestamp = timestamp;
            this.connectionId = connectionId;
        }
    }

    /**
     * Add a new MCP trace with LSP-style formatting.
     */
    public void addTrace(String direction, String connectionId, String message) {
        Instant now = Instant.now();
        String formattedMessage = formatMessage(direction, connectionId, message, now);

        McpTrace trace = new McpTrace(
                direction,
                connectionId,
                formattedMessage,
                now
        );

        traces.add(trace);

        // Keep only last MAX_TRACES
        if (traces.size() > MAX_TRACES) {
            traces.remove(0);
        }

        // Fire CDI event for SSE broadcasting
        Log.infof("Firing MCP trace event: %s [%s] - message length: %d", direction, connectionId, message.length());
        traceEvent.fire(trace);

        Log.infof("MCP trace added to collection (total: %d): %s [%s]", traces.size(), direction, connectionId);
    }

    /**
     * Format MCP message in LSP-style with method name, ID, and timing.
     */
    private String formatMessage(String direction, String connectionId, String message, Instant now) {
        try {
            Log.infof("Formatting MCP message: direction=%s, connectionId=%s, messageLength=%d",
                     direction, connectionId, message.length());
            Log.infof("Message content: %s", message.substring(0, Math.min(100, message.length())));

            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            Log.infof("Parsed JSON successfully");

            String method = json.has("method") ? json.get("method").getAsString() : null;
            JsonElement idElement = json.get("id");
            String id = null;
            if (idElement != null && !idElement.isJsonNull()) {
                // ID can be number or string in JSON-RPC
                id = idElement.isJsonPrimitive() ? idElement.getAsString() : idElement.toString();
            }

            boolean isNotification = id == null && method != null;
            boolean isRequest = id != null && method != null;
            boolean isResponse = id != null && method == null;

            Log.infof("Parsed: method=%s, id=%s, isRequest=%s, isResponse=%s, isNotification=%s",
                     method, id, isRequest, isResponse, isNotification);

            String header;
            String key = connectionId + ":" + (id != null ? id : "");

            if (isRequest) {
                // Sending request: track it
                header = String.format("[Trace - %s] Sending request '%s - (%s)'",
                        formatTime(now), method, id);
                pendingRequests.put(key, new PendingRequest(method, now, connectionId));
            } else if (isResponse) {
                // Receiving response: calculate duration
                PendingRequest pending = pendingRequests.remove(key);
                if (pending != null) {
                    long durationMs = Duration.between(pending.timestamp, now).toMillis();
                    header = String.format("[Trace - %s] Received response '%s - (%s)' in %dms",
                            formatTime(now), pending.method, id, durationMs);
                } else {
                    header = String.format("[Trace - %s] Received response '(%s)'",
                            formatTime(now), id);
                }
            } else if (isNotification && "sent".equals(direction)) {
                header = String.format("[Trace - %s] Sending notification '%s'",
                        formatTime(now), method);
            } else if (isNotification && "received".equals(direction)) {
                header = String.format("[Trace - %s] Received notification '%s'",
                        formatTime(now), method);
            } else {
                // Fallback: just show direction
                header = String.format("[Trace - %s] MCP message %s [%s]",
                        formatTime(now), direction, connectionId);
            }

            // Pretty-print JSON body
            String body = new com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(json);

            return header + "\n" + body;

        } catch (Exception e) {
            // If JSON parsing fails, return original message
            Log.errorf(e, "Failed to parse MCP message for formatting, direction=%s, connectionId=%s, messageStart=%s",
                      direction, connectionId, message.substring(0, Math.min(50, message.length())));
            return String.format("[Trace - %s] MCP %s (PARSE ERROR) [%s]\n%s",
                    formatTime(now), direction, connectionId, message);
        }
    }

    /**
     * Format timestamp as HH:mm:ss.
     */
    private String formatTime(Instant instant) {
        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
        return String.format("%02d:%02d:%02d",
                zdt.getHour(), zdt.getMinute(), zdt.getSecond());
    }

    /**
     * Get all traces.
     */
    public List<McpTrace> getAllTraces() {
        return List.copyOf(traces);
    }

    /**
     * Get traces with limit.
     */
    public List<McpTrace> getTraces(int limit) {
        int size = traces.size();
        int start = Math.max(0, size - limit);
        return List.copyOf(traces.subList(start, size));
    }

    /**
     * Clear all traces.
     */
    public void clearTraces() {
        traces.clear();
        Log.info("MCP traces cleared");
    }
}
