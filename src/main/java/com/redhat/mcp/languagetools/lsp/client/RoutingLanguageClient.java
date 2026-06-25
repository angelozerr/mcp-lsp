package com.redhat.mcp.languagetools.lsp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.lsp.RequestRouter;
import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Language client wrapper that adds bindRequest routing support.
 * Implements both LanguageClient (for standard methods) and Endpoint (for bindRequest routing).
 * Uses @JsonDelegate to expose delegate's @JsonNotification and @JsonRequest methods.
 *
 * GenericEndpoint will:
 * 1. Scan @JsonDelegate for methods like language/status → called with correct type
 * 2. For unmatched requests, call our Endpoint.request() → routes bindRequest
 */
public class RoutingLanguageClient implements LanguageClient, Endpoint {

    private static final Logger LOG = Logger.getLogger(RoutingLanguageClient.class);

    private final LanguageClient delegate;
    private final LspServer lspServer;

    public RoutingLanguageClient(LanguageClient delegate, LspServer lspServer) {
        this.delegate = delegate;
        this.lspServer = lspServer;
    }

    /**
     * Return the real client type for MessageJsonHandler to scan.
     * This ensures language/status gets registered as StatusReport in supportedMethods.
     */
    public LanguageClient getRealClient() {
        return delegate;
    }

    // ===== LanguageClient delegation =====

    @Override
    public void telemetryEvent(Object object) {
        delegate.telemetryEvent(object);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        delegate.publishDiagnostics(diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        delegate.showMessage(messageParams);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return delegate.showMessageRequest(requestParams);
    }

    @Override
    public void logMessage(MessageParams message) {
        delegate.logMessage(message);
    }

    // ===== Endpoint implementation for bindRequest routing =====

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        LOG.debugf("[%s] Endpoint.request() called for: %s", lspServer.getConfig().getId(), method);

        // Check if this is a bindRequest that should be routed
        BindRequestInfo bindInfo = findBindRequestInfo(method);

        if (bindInfo != null) {
            RequestRouter router = lspServer.getRequestRouter();
            if (router != null) {
                LOG.infof("[%s] Routing bindRequest %s to server %s (mode: %s)",
                    lspServer.getConfig().getId(), method, bindInfo.targetServerId, bindInfo.mode);
                return router.routeRequest(bindInfo.targetServerId, method, parameter, bindInfo.mode);
            } else {
                LOG.warnf("[%s] No request router available for bindRequest %s",
                    lspServer.getConfig().getId(), method);
            }
        }

        // Not a bindRequest - return MethodNotFound error
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new ResponseErrorException(
            new ResponseError(ResponseErrorCode.MethodNotFound, "Method not found: " + method, null)));
        return future;
    }

    @Override
    public void notify(String method, Object parameter) {
        // Notifications are handled via @JsonNotification on delegate
        LOG.debugf("[%s] Endpoint.notify() called (should not happen): %s", lspServer.getConfig().getId(), method);
    }

    // ===== bindRequest routing logic =====

    private static class BindRequestInfo {
        final String targetServerId;
        final String mode; // "executeCommand" or "direct"

        BindRequestInfo(String targetServerId, String mode) {
            this.targetServerId = targetServerId;
            this.mode = mode;
        }
    }

    /**
     * Find which server this request should be routed to based on bindRequest declarations.
     */
    private BindRequestInfo findBindRequestInfo(String requestMethod) {
        LspServerConfig config = lspServer.getConfig();

        if (config.getContributes() == null || config.getContributes().getContributions() == null) {
            return null;
        }

        // Look through all contributes.{serverId}.bindRequest arrays
        for (Map.Entry<String, JsonElement> entry : config.getContributes().getContributions().entrySet()) {
            String targetServerId = entry.getKey();
            JsonElement contrib = entry.getValue();

            if (!contrib.isJsonObject()) {
                continue;
            }

            JsonObject contribObj = contrib.getAsJsonObject();
            if (!contribObj.has("bindRequest") || !contribObj.get("bindRequest").isJsonArray()) {
                continue;
            }

            JsonArray bindRequests = contribObj.get("bindRequest").getAsJsonArray();

            for (JsonElement req : bindRequests) {
                if (req.isJsonPrimitive() && req.getAsString().equals(requestMethod)) {
                    // Found! Determine the mode
                    String mode = "executeCommand"; // Default mode
                    if (contribObj.has("bindMode") && contribObj.get("bindMode").isJsonPrimitive()) {
                        mode = contribObj.get("bindMode").getAsString();
                    }
                    LOG.debugf("[%s] Found bindRequest match: %s -> %s (mode: %s)",
                        config.getId(), requestMethod, targetServerId, mode);
                    return new BindRequestInfo(targetServerId, mode);
                }
            }
        }

        return null;
    }
}
