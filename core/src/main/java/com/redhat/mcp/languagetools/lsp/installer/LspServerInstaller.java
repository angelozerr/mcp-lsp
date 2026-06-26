package com.redhat.mcp.languagetools.lsp.installer;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;

/**
 * Interface for language server installers.
 */
public interface LspServerInstaller {

    /**
     * Check if the server is already installed.
     */
    boolean isInstalled(LspServerConfig config);

    /**
     * Install the language server.
     */
    CompletableFuture<Path> install(LspServerConfig config);

    /**
     * Get the installation directory.
     */
    Path getInstallDir(LspServerConfig config);
}
