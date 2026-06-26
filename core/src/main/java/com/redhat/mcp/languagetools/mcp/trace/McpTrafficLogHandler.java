package com.redhat.mcp.languagetools.mcp.trace;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Custom log handler that intercepts MCP traffic logs and forwards them to McpTraceCollector.
 * Captures the FULL message before Quarkus MCP truncates it.
 */
@ApplicationScoped
@Startup
public class McpTrafficLogHandler extends Handler {

    private static final Logger LOG = Logger.getLogger(McpTrafficLogHandler.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Inject
    McpTraceCollector traceCollector;

    @PostConstruct
    void init() {
        // Attach to the java.util.logging logger for MCP traffic
        java.util.logging.Logger mcpLogger = java.util.logging.Logger.getLogger("io.quarkus.mcp.server.traffic");

        // Set handler level to ALL to receive all messages
        this.setLevel(java.util.logging.Level.ALL);

        mcpLogger.addHandler(this);
        LOG.infof("MCP traffic log handler registered for logger: %s (level: %s, handler level: %s)",
                 mcpLogger.getName(), mcpLogger.getLevel(), this.getLevel());
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }

        try {
            String message = record.getMessage();

            // Extract direction and connection ID
            // Format from TrafficLogger: "MCP message received [connectionId]:\n\n{json}"
            String direction = message.contains("received") ? "received" : "sent";
            String connectionId = extractConnectionId(message);
            String jsonContent = extractJsonContent(message);

            // Simple and fast: just remove leading/trailing blank lines
            String cleanJson = jsonContent.trim();

            LOG.infof("McpTrafficLogHandler: Passing to collector - direction=%s, connectionId=%s, jsonLength=%d",
                     direction, connectionId, cleanJson.length());

            // Pass raw JSON to collector - it will format it with method names and timing
            traceCollector.addTrace(direction, connectionId, cleanJson);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process MCP traffic log");
        }
    }

    private String extractConnectionId(String message) {
        // Extract from "MCP message received [connectionId]:"
        int start = message.indexOf('[');
        int end = message.indexOf(']');
        if (start != -1 && end != -1 && end > start) {
            return message.substring(start + 1, end);
        }
        return "unknown";
    }

    private String extractJsonContent(String message) {
        // Extract JSON after the ":\n\n" separator
        // Quarkus logs: "MCP message received [id]:\n\n{json}"
        int separator = message.indexOf(":\n\n");
        if (separator != -1) {
            // Skip past ":\n\n"
            String json = message.substring(separator + 3);

            // Remove all leading whitespace including newlines and spaces
            // but preserve the JSON content formatting
            while (json.length() > 0 && (json.charAt(0) == '\n' || json.charAt(0) == '\r' || json.charAt(0) == ' ' || json.charAt(0) == '\t')) {
                json = json.substring(1);
            }

            return json;
        }
        return message;
    }

    @Override
    public void flush() {
        // Nothing to flush
    }

    @Override
    public void close() throws SecurityException {
        // Nothing to close
    }
}
