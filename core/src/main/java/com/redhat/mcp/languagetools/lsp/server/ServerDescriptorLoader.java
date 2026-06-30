package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.dap.server.DapServerConfig;
import com.redhat.mcp.languagetools.dap.server.DapServerDescriptorLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Facade for loading LSP and DAP server descriptors.
 * Delegates to specialized loaders: LspServerDescriptorLoader and DapServerDescriptorLoader.
 *
 * @deprecated Use LspServerDescriptorLoader or DapServerDescriptorLoader directly.
 */
@ApplicationScoped
public class ServerDescriptorLoader {

    private static final Logger LOG = Logger.getLogger(ServerDescriptorLoader.class);

    @Inject
    LspServerDescriptorLoader lspLoader;

    @Inject
    DapServerDescriptorLoader dapLoader;

    /**
     * Load a bundled LSP server configuration.
     */
    public LspServerConfig loadBundled(String serverId) throws IOException {
        return lspLoader.loadBundled(serverId);
    }

    /**
     * Load a user-defined LSP server configuration from file.
     */
    public LspServerConfig loadFromFile(Path configFile) throws IOException {
        return lspLoader.loadFromFile(configFile);
    }

    /**
     * Load all bundled LSP server configurations.
     */
    public Map<String, LspServerConfig> loadAllBundled() {
        return lspLoader.loadAllBundled();
    }

    /**
     * Load a bundled DAP server configuration.
     */
    public DapServerConfig loadDapBundled(String serverId) throws IOException {
        return dapLoader.loadBundled(serverId);
    }

    /**
     * Load all bundled DAP server configurations.
     */
    public Map<String, DapServerConfig> loadAllDapBundled() {
        return dapLoader.loadAllBundled();
    }
}
