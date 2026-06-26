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
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generic LSP client implementation with support for capability registration and bindRequest routing.
 * Implements Endpoint to handle bindRequest routing declared in server.json.
 */
public class GenericLanguageClient implements LanguageClient, Endpoint {

    private static final Logger LOG = Logger.getLogger(GenericLanguageClient.class);

    protected final LspServer lspServer;

    public GenericLanguageClient(LspServer lspServer) {
        this.lspServer = lspServer;
    }

    @Override
    public void telemetryEvent(Object object) {
        // Ignore telemetry for now
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        LOG.debugf("Diagnostics published for: %s", diagnostics.getUri());
        lspServer.getDiagnosticsCache().put(diagnostics.getUri(), diagnostics.getDiagnostics());
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        LOG.infof("%s message: %s", lspServer.getConfig().getId(), messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.infof("%s log: %s", lspServer.getConfig().getId(), message.getMessage());
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        LOG.infof("[%s] Registering capabilities", lspServer.getConfig().getId());
        lspServer.getClientFeatures().registerCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        LOG.infof("[%s] Unregistering capabilities", lspServer.getConfig().getId());
        lspServer.getClientFeatures().unregisterCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    // ===== Endpoint implementation for bindRequest routing =====

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        LOG.debugf("[%s] GenericLanguageClient.request() called for: %s", lspServer.getConfig().getId(), method);

        // Check if this is a bindRequest declared in server.json
        BindRequestInfo bindInfo = findBindRequestInfo(method);

        if (bindInfo != null) {
            RequestRouter router = lspServer.getRequestRouter();
            if (router != null) {
                LOG.infof("[%s] Routing bindRequest %s to server %s (mode: %s)",
                    lspServer.getConfig().getId(), method, bindInfo.targetServerId, bindInfo.mode);
                return router.routeRequest(bindInfo.targetServerId, method, parameter, bindInfo.mode);
            }
        }

        // Not a bindRequest - return MethodNotFound
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new ResponseErrorException(
            new ResponseError(ResponseErrorCode.MethodNotFound, "Method not found: " + method, null)));
        return future;
    }

    @Override
    public void notify(String method, Object parameter) {
        // Notifications are handled via @JsonNotification
        LOG.debugf("[%s] GenericLanguageClient.notify() called: %s", lspServer.getConfig().getId(), method);
    }

    // ===== bindRequest routing logic =====

    private static class BindRequestInfo {
        final String targetServerId;
        final String mode;

        BindRequestInfo(String targetServerId, String mode) {
            this.targetServerId = targetServerId;
            this.mode = mode;
        }
    }

    private BindRequestInfo findBindRequestInfo(String requestMethod) {
        LspServerConfig config = lspServer.getConfig();

        if (config.getContributes() == null || config.getContributes().getContributions() == null) {
            return null;
        }

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
                    String mode = "executeCommand";
                    if (contribObj.has("bindMode") && contribObj.get("bindMode").isJsonPrimitive()) {
                        mode = contribObj.get("bindMode").getAsString();
                    }
                    return new BindRequestInfo(targetServerId, mode);
                }
            }
        }

        return null;
    }
}
