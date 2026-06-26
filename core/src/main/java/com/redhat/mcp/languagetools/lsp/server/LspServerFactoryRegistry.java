package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry for LSP server factories.
 * Uses SPI (ServiceLoader) to discover custom server factory implementations.
 */
public class LspServerFactoryRegistry {

    private static final Logger LOG = Logger.getLogger(LspServerFactoryRegistry.class);
    private static final Map<String, LspServerFactory> factories = new HashMap<>();

    /**
     * Private implementation of LspServerContext.
     */
    private static class LspServerContextImpl implements LspServerContext {
        private final PathManager pathManager;
        private final List<LspServerConfig> allServerConfigs;
        private final URI workspaceRoot;
        private final Path workspaceDataDir;
        private final Path serverHome;
        private final LspTraceCollector traceCollector;

        LspServerContextImpl(PathManager pathManager, List<LspServerConfig> allServerConfigs,
                             URI workspaceRoot, Path workspaceDataDir, Path serverHome,
                             LspTraceCollector traceCollector) {
            this.pathManager = pathManager;
            this.allServerConfigs = allServerConfigs;
            this.workspaceRoot = workspaceRoot;
            this.workspaceDataDir = workspaceDataDir;
            this.serverHome = serverHome;
            this.traceCollector = traceCollector;
        }

        @Override
        public PathManager getPathManager() {
            return pathManager;
        }

        @Override
        public List<LspServerConfig> getAllServerConfigs() {
            return allServerConfigs;
        }

        @Override
        public URI getWorkspaceRoot() {
            return workspaceRoot;
        }

        @Override
        public Path getWorkspaceDataDir() {
            return workspaceDataDir;
        }

        @Override
        public Path getServerHome() {
            return serverHome;
        }

        @Override
        public LspTraceCollector getTraceCollector() {
            return traceCollector;
        }
    }

    static {
        // Load all LspServerFactory implementations via SPI
        ServiceLoader<LspServerFactory> loader = ServiceLoader.load(LspServerFactory.class);
        for (LspServerFactory factory : loader) {
            factories.put(factory.getServerId(), factory);
            LOG.infof("Registered custom LSP server factory for: %s", factory.getServerId());
        }
    }

    /**
     * Create a LspServerContext instance.
     * This is used internally by WorkspaceManager to create the context.
     */
    public static LspServerContext createContext(PathManager pathManager, List<LspServerConfig> allServerConfigs,
                                                  URI workspaceRoot, Path workspaceDataDir, Path serverHome,
                                                  LspTraceCollector traceCollector) {
        return new LspServerContextImpl(pathManager, allServerConfigs, workspaceRoot, workspaceDataDir, serverHome, traceCollector);
    }

    /**
     * Create an LSP server instance based on the config.
     * Priority:
     * 1. Custom factory registered via SPI (e.g., JdtLsServer) - for servers with special needs
     * 2. ClasspathExtensibleLspServer - default for all other servers
     *
     * ClasspathExtensibleLspServer automatically detects and applies jarExtensions contributions.
     * If no extensions exist, it behaves exactly like the base LspServer.
     */
    public static LspServer createServer(LspServerConfig config, LspServerContext context) {

        // Check for custom factory (highest priority)
        LspServerFactory factory = factories.get(config.getId());
        if (factory != null) {
            LOG.infof("Creating custom LSP server for %s (workspace: %s)", config.getId(), context.getWorkspaceRoot());
            return factory.createServer(config, context);
        }

        // Default: use classpath-extensible server (supports jarExtensions contributions)
        LOG.debugf("Creating classpath-extensible LSP server for %s", config.getId());
        return new ClasspathExtensibleLspServer(config, context);
    }
}
