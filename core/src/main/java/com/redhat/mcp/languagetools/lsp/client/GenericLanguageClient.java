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
 * Generic LSP client implementation with support for capability registration, bindRequest and bindNotification routing.
 * Implements Endpoint to handle bindRequest and bindNotification routing declared in server.json.
 *
 * bindRequest: defaults to "executeCommand" mode (workspace/executeCommand)
 * bindNotification: defaults to "direct" mode (direct method call)
 */
public class GenericLanguageClient implements LanguageClient, Endpoint {

    private static final Logger LOG = Logger.getLogger(GenericLanguageClient.class);

    // JSON field names
    private static final String BIND_REQUEST = "bindRequest";
    private static final String BIND_NOTIFICATION = "bindNotification";
    private static final String MODE = "mode";
    private static final String METHODS = "methods";
    private static final String METHOD = "method";
    private static final String TARGET_METHOD = "targetMethod";

    // Bind mode enum
    public enum BindMode {
        EXECUTE_COMMAND("executeCommand"),
        DIRECT("direct");

        private final String value;

        BindMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static BindMode fromString(String mode) {
            for (BindMode m : values()) {
                if (m.value.equals(mode)) {
                    return m;
                }
            }
            return EXECUTE_COMMAND; // Default fallback
        }
    }

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
        // Current server ID (the one receiving the custom request from its LSP server)
        // Example: "microprofile" receives "microprofile/java/projectInfo"
        //      or: "qute" receives "qute/template/project"
        String serverId = lspServer.getConfig().getId();
        LOG.infof("[%s] GenericLanguageClient.request() called for: %s", serverId, method);

        // Check if this is a bindRequest declared in server.json
        BindInfo bindInfo = findBindInfo(method, BIND_REQUEST, BindMode.EXECUTE_COMMAND);

        if (bindInfo != null) {
            // Target server ID (the one that will handle the custom request)
            // Example: "jdtls" when microprofile declares bindRequest: ["microprofile/java/projectInfo"]
            //      or: "jdtls" when qute declares bindRequest: ["qute/template/project"]
            String targetServerId = bindInfo.targetServerId;
            // Target method name (may differ from source method)
            // Example: "qute/template/project" → "jdtls/qute/getProject"
            String targetMethod = bindInfo.targetMethod();
            // Routing mode (EXECUTE_COMMAND or DIRECT)
            // Default: EXECUTE_COMMAND (via workspace/executeCommand)
            BindMode bindMode = bindInfo.mode();
            RequestRouter router = lspServer.getRequestRouter();
            if (router != null) {
                LOG.infof("[%s] Routing bindRequest %s to server %s as %s (mode: %s)",
                    serverId, method, targetServerId, targetMethod, bindMode.getValue());
                return router.routeRequest(targetServerId, targetMethod, parameter, bindMode.getValue());
            } else {
                LOG.errorf("[%s] RequestRouter is NULL! Cannot route bindRequest %s to %s",
                    serverId, method, targetServerId);
            }
        } else {
            LOG.warnf("[%s] No bindInfo found for method: %s (contributes=%s)",
                serverId, method, lspServer.getConfig().getContributes());
        }

        // Not a bindRequest - return MethodNotFound
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new ResponseErrorException(
            new ResponseError(ResponseErrorCode.MethodNotFound, "Method not found: " + method, null)));
        return future;
    }

    @Override
    public void notify(String method, Object parameter) {
        // Current server ID (the one receiving the custom notification from its LSP server)
        // Example: "microprofile" receives "microprofile/propertiesChanged"
        //      or: "qute" receives "qute/dataModelChanged"
        String serverId = lspServer.getConfig().getId();
        LOG.debugf("[%s] GenericLanguageClient.notify() called: %s", serverId, method);

        // Check if this is a bindNotification declared in server.json
        BindInfo bindInfo = findBindInfo(method, BIND_NOTIFICATION, BindMode.DIRECT);

        if (bindInfo != null) {
            // Target server ID (the one that will receive the custom notification)
            // Example: "jdtls" when microprofile declares bindNotification: ["microprofile/propertiesChanged"]
            //      or: "jdtls" when qute declares bindNotification: ["qute/dataModelChanged"]
            String targetServerId = bindInfo.targetServerId;
            // Target method name (may differ from source method)
            // Example: "qute/dataModelChanged" → "jdtls/qute/modelChanged"
            String targetMethod = bindInfo.targetMethod();
            // Routing mode (DIRECT by default, or EXECUTE_COMMAND)
            // Default: DIRECT (direct method call, not via workspace/executeCommand)
            BindMode bindMode = bindInfo.mode();
            RequestRouter router = lspServer.getRequestRouter();
            if (router != null) {
                LOG.infof("[%s] Routing bindNotification %s to server %s as %s (mode: %s)",
                    serverId, method, targetServerId, targetMethod, bindMode.getValue());

                // Send notification asynchronously (fire and forget)
                router.routeRequest(targetServerId, targetMethod, parameter, bindMode.getValue())
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "[%s] Failed to route bindNotification %s",
                            serverId, method);
                        return null;
                    });
            }
        }
    }

    // ===== bindRequest and bindNotification routing logic =====

    private record BindInfo(String targetServerId, String targetMethod, BindMode mode) {
    }

    /**
     * Find bind information for a method in server.json contributes section.
     * Works for both bindRequest and bindNotification.
     *
     * @param method The method name to find (e.g., "qute/template/project")
     * @param bindKey The bind type key ("bindRequest" or "bindNotification")
     * @param defaultMode The default mode to use if not specified
     * @return BindInfo if found, null otherwise
     */
    private BindInfo findBindInfo(String method, String bindKey, BindMode defaultMode) {
        LspServerConfig config = lspServer.getConfig();

        if (config.getContributes() == null || config.getContributes().getContributions() == null) {
            LOG.debugf("[%s] No contributes found for findBindInfo(%s, %s)",
                config.getId(), method, bindKey);
            return null;
        }

        LOG.debugf("[%s] Searching for %s in %d contributions",
            config.getId(), method, config.getContributes().getContributions().size());

        for (Map.Entry<String, JsonElement> entry : config.getContributes().getContributions().entrySet()) {
            String targetServerId = entry.getKey();
            JsonElement contrib = entry.getValue();

            LOG.debugf("[%s] Checking contribution to target server: %s", config.getId(), targetServerId);

            if (!contrib.isJsonObject()) {
                LOG.debugf("[%s] Contribution is not JsonObject, skipping", config.getId());
                continue;
            }

            JsonObject contribObj = contrib.getAsJsonObject();
            if (!contribObj.has(bindKey)) {
                LOG.debugf("[%s] No %s found in contribution to %s", config.getId(), bindKey, targetServerId);
                continue;
            }

            JsonElement bindElement = contribObj.get(bindKey);

            // Format 1: Simple array ["method1", "method2", ...]
            if (bindElement.isJsonArray()) {
                JsonArray bindArray = bindElement.getAsJsonArray();
                for (JsonElement element : bindArray) {
                    BindInfo info = parseBindEntry(element, method, targetServerId, defaultMode);
                    if (info != null) {
                        return info;
                    }
                }
            }
            // Format 2: Object with mode { "mode": "direct", "methods": [...] }
            else if (bindElement.isJsonObject()) {
                JsonObject bindObj = bindElement.getAsJsonObject();
                BindMode mode = defaultMode;
                if (bindObj.has(MODE) && bindObj.get(MODE).isJsonPrimitive()) {
                    mode = BindMode.fromString(bindObj.get(MODE).getAsString());
                }
                if (bindObj.has(METHODS) && bindObj.get(METHODS).isJsonArray()) {
                    JsonArray methods = bindObj.get(METHODS).getAsJsonArray();
                    for (JsonElement element : methods) {
                        BindInfo info = parseBindEntry(element, method, targetServerId, mode);
                        if (info != null) {
                            return info;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Parse a single bind entry (works for both bindRequest and bindNotification).
     * Supports two formats:
     * - Simple string: "qute/template/project"
     * - Object: { "method": "qute/template/project", "targetMethod": "jdtls/qute/getProject", "mode": "direct" }
     */
    private BindInfo parseBindEntry(JsonElement entry, String sourceMethod, String targetServerId, BindMode defaultMode) {
        // Simple string: "qute/template/project"
        if (entry.isJsonPrimitive() && entry.getAsString().equals(sourceMethod)) {
            return new BindInfo(targetServerId, sourceMethod, defaultMode);
        }
        // Object: { "method": "qute/template/project", "targetMethod": "jdtls/qute/getProject", "mode": "direct" }
        else if (entry.isJsonObject()) {
            JsonObject obj = entry.getAsJsonObject();
            if (obj.has(METHOD) && obj.get(METHOD).isJsonPrimitive()) {
                String method = obj.get(METHOD).getAsString();
                if (method.equals(sourceMethod)) {
                    String targetMethod = sourceMethod; // Default: same name
                    if (obj.has(TARGET_METHOD) && obj.get(TARGET_METHOD).isJsonPrimitive()) {
                        targetMethod = obj.get(TARGET_METHOD).getAsString();
                    }
                    BindMode mode = defaultMode; // Use default from parent
                    if (obj.has(MODE) && obj.get(MODE).isJsonPrimitive()) {
                        mode = BindMode.fromString(obj.get(MODE).getAsString());
                    }
                    return new BindInfo(targetServerId, targetMethod, mode);
                }
            }
        }
        return null;
    }

}
