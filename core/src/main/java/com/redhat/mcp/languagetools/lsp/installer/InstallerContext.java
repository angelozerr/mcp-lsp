package com.redhat.mcp.languagetools.lsp.installer;

import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Context for installer execution.
 * Holds properties and provides logging + trace forwarding.
 */
public class InstallerContext {

    private static final Logger LOG = Logger.getLogger(InstallerContext.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Map<String, Object> properties = new HashMap<>();
    private final StringBuilder errorLog = new StringBuilder();
    private LspTraceCollector traceCollector;
    private String workspaceUri;
    private String serverId;
    private String serverName;

    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    public String getPropertyAsString(String key) {
        Object value = properties.get(key);
        return value != null ? value.toString() : null;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setTraceCollector(LspTraceCollector traceCollector, String workspaceUri, String serverId, String serverName) {
        this.traceCollector = traceCollector;
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
        this.serverName = serverName;
    }

    public void log(String message) {
        LOG.info(message);
        sendTrace(message, false);
    }

    public void logError(String message) {
        LOG.error(message);
        errorLog.append(message).append("\n");
        sendTrace("[ERROR] " + message, true);
    }

    public void logError(String message, Throwable t) {
        LOG.error(message, t);
        String fullMessage = "[ERROR] " + message + "\n" + t.toString();
        if (t.getMessage() != null) {
            fullMessage += ": " + t.getMessage();
        }
        errorLog.append(fullMessage).append("\n");
        sendTrace(fullMessage, true);
    }

    public String getErrorLog() {
        return errorLog.toString().trim();
    }

    public boolean hasErrors() {
        return errorLog.length() > 0;
    }

    private void sendTrace(String message, boolean isError) {
        if (traceCollector != null && workspaceUri != null && serverId != null) {
            String time = LocalTime.now().format(TIME_FORMATTER);
            String formattedMessage = String.format("[Install - %s] %s", time, message);

            traceCollector.addTrace(
                workspaceUri,
                serverId,
                serverName != null ? serverName : serverId,
                LspTraceMessage.MessageDirection.SERVER_TO_CLIENT,
                formattedMessage
            );
        }
    }

    /**
     * Resolve variables in a string (e.g., ${output.dir}, ${user.home}, $SERVER_HOME$).
     */
    public String resolveVariables(String input) {
        if (input == null) {
            return null;
        }

        String result = input;

        // System properties
        result = result.replace("${user.home}", System.getProperty("user.home"));
        result = result.replace("$USER_HOME$", System.getProperty("user.home"));

        // Special variables
        String serverHome = getPropertyAsString("server.home");
        if (serverHome != null) {
            result = result.replace("$SERVER_HOME$", serverHome);
        }

        // Context properties
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder) && entry.getValue() != null) {
                result = result.replace(placeholder, entry.getValue().toString());
            }
        }

        return result;
    }
}
