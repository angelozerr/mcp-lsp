package com.redhat.mcp.languagetools.lsp.server;

/**
 * SPI interface for creating custom LSP server implementations.
 * Extensions implement this interface and register via ServiceLoader.
 */
public interface LspServerFactory {

    /**
     * Get the server ID that this factory handles (e.g., "jdtls", "microprofile").
     */
    String getServerId();

    /**
     * Create a custom LSP server instance.
     *
     * @param config This server's configuration
     * @param context Server creation context with all dependencies
     */
    LspServer createServer(LspServerConfig config, LspServerContext context);
}
