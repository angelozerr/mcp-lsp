package com.redhat.mcp.languagetools.lsp.trace;

import com.google.gson.GsonBuilder;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traces LSP messages in a format similar to lsp4ij.
 * Adapted from lsp4ij TracingMessageConsumer.
 */
public class TracingMessageConsumer {

    private static MessageJsonHandler toStringInstance;

    private final LspTraceCollector collector;
    private final String workspaceUri;
    private final String serverId;
    private final String serverName;
    private final Map<String, RequestMetadata> sentRequests;
    private final Map<String, RequestMetadata> receivedRequests;
    private final Clock clock;
    private final DateTimeFormatter dateTimeFormatter;

    public TracingMessageConsumer(LspTraceCollector collector, String workspaceUri, String serverId, String serverName) {
        this.collector = collector;
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
        this.serverName = serverName;
        this.sentRequests = new ConcurrentHashMap<>();
        this.receivedRequests = new ConcurrentHashMap<>();
        this.clock = Clock.systemDefaultZone();
        this.dateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(clock.getZone());
    }

    /**
     * Log a message and determine direction based on MessageConsumer type.
     */
    public void log(Message message, MessageConsumer messageConsumer) {
        final Instant now = clock.instant();
        final String date = dateTimeFormatter.format(now);

        LspTraceMessage.MessageDirection direction;
        String logContent;

        if (messageConsumer instanceof StreamMessageConsumer) {
            direction = LspTraceMessage.MessageDirection.CLIENT_TO_SERVER;
            logContent = consumeMessageSending(message, now, date);
        } else if (messageConsumer instanceof RemoteEndpoint) {
            direction = LspTraceMessage.MessageDirection.SERVER_TO_CLIENT;
            logContent = consumeMessageReceiving(message, now, date);
        } else {
            direction = LspTraceMessage.MessageDirection.SERVER_TO_CLIENT;
            logContent = String.format("Unknown MessageConsumer type: %s", messageConsumer);
        }

        collector.addTrace(workspaceUri, serverId, serverName, direction, logContent);
    }

    private String consumeMessageSending(Message message, Instant now, String date) {
        if (message instanceof RequestMessage) {
            RequestMessage requestMessage = (RequestMessage) message;
            String id = requestMessage.getId();
            String method = requestMessage.getMethod();
            RequestMetadata requestMetadata = new RequestMetadata(method, now);
            sentRequests.put(id, requestMetadata);
            Object params = requestMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Sending request '%s - (%s)'.\nParams: %s\n\n", date, method, id, paramsJson);
        } else if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            String id = responseMessage.getId();
            RequestMetadata requestMetadata = receivedRequests.remove(id);
            String method = getMethod(requestMetadata);
            String latencyMillis = getLatencyMillis(requestMetadata, now);
            Object result = responseMessage.getResult();
            String resultJson = toJsonString(result);
            String resultTrace = getResultTrace(resultJson, null);
            return String.format("[Trace - %s] Sending response '%s - (%s)'. Processing request took %sms\n%s\n\n", date, method, id, latencyMillis, resultTrace);
        } else if (message instanceof NotificationMessage) {
            NotificationMessage notificationMessage = (NotificationMessage) message;
            String method = notificationMessage.getMethod();
            Object params = notificationMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Sending notification '%s'\nParams: %s\n\n", date, method, paramsJson);
        } else {
            return String.format("Unknown message type: %s", message);
        }
    }

    private String consumeMessageReceiving(Message message, Instant now, String date) {
        if (message instanceof RequestMessage) {
            RequestMessage requestMessage = (RequestMessage) message;
            String method = requestMessage.getMethod();
            String id = requestMessage.getId();
            RequestMetadata requestMetadata = new RequestMetadata(method, now);
            receivedRequests.put(id, requestMetadata);
            Object params = requestMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Received request '%s - (%s)'\nParams: %s\n\n", date, method, id, paramsJson);
        } else if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            String id = responseMessage.getId();
            RequestMetadata requestMetadata = sentRequests.remove(id);
            String method = getMethod(requestMetadata);
            String latencyMillis = getLatencyMillis(requestMetadata, now);
            Object result = responseMessage.getResult();
            String resultJson = toJsonString(result);
            Object error = responseMessage.getError();
            String errorJson = toJsonString(error);
            String resultTrace = getResultTrace(resultJson, errorJson);
            return String.format("[Trace - %s] Received response '%s - (%s)' in %sms.\n%s\n\n", date, method, id, latencyMillis, resultTrace);
        } else if (message instanceof NotificationMessage) {
            NotificationMessage notificationMessage = (NotificationMessage) message;
            String method = notificationMessage.getMethod();
            Object params = notificationMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Received notification '%s'\nParams: %s\n\n", date, method, paramsJson);
        } else {
            return String.format("Unknown message type: %s", message);
        }
    }

    private static String toJsonString(Object object) {
        if (toStringInstance == null) {
            toStringInstance = new MessageJsonHandler(Collections.emptyMap(), gsonBuilder -> {
                gsonBuilder.setPrettyPrinting();
            });
        }
        return toStringInstance.getGson().toJson(object);
    }

    private static String getResultTrace(String resultJson, String errorJson) {
        StringBuilder result = new StringBuilder();
        if (resultJson != null && !"null".equals(resultJson)) {
            result.append("Result: ");
            result.append(resultJson);
        } else {
            result.append("No response returned.");
        }
        if (errorJson != null && !"null".equals(errorJson)) {
            result.append("\nError: ");
            result.append(errorJson);
        }
        return result.toString();
    }

    private static String getMethod(RequestMetadata requestMetadata) {
        return requestMetadata != null ? requestMetadata.method : "<unknown>";
    }

    private static String getLatencyMillis(RequestMetadata requestMetadata, Instant now) {
        return requestMetadata != null ? String.valueOf(now.toEpochMilli() - requestMetadata.start.toEpochMilli()) : "?";
    }

    public LspTraceCollector getCollector() {
        return collector;
    }

    private static class RequestMetadata {
        final String method;
        final Instant start;

        public RequestMetadata(String method, Instant start) {
            this.method = method;
            this.start = start;
        }
    }
}
