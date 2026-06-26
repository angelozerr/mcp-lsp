package com.redhat.mcp.languagetools.lsp;

import java.util.concurrent.CompletableFuture;

/**
 * Routes requests between LSP servers.
 * Used for bindRequest mechanism where one server delegates requests to another.
 */
public interface RequestRouter {

    /**
     * Route a request to another server.
     *
     * @param targetServerId Target server ID (e.g., "jdtls")
     * @param method Request method (e.g., "microprofile/java/projectInfo")
     * @param params Request parameters
     * @param mode Routing mode ("executeCommand" or "direct")
     * @return CompletableFuture with the response
     */
    CompletableFuture<Object> routeRequest(String targetServerId, String method, Object params, String mode);
}
