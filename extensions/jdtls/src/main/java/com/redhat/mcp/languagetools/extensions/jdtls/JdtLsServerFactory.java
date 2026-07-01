package com.redhat.mcp.languagetools.extensions.jdtls;

import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.LspServerContext;
import com.redhat.mcp.languagetools.lsp.server.LspServerFactory;

/**
 * Factory for creating JDT.LS custom server instances.
 */
public class JdtLsServerFactory implements LspServerFactory {

    @Override
    public String getServerId() {
        return "jdtls";
    }

    @Override
    public LspServer createServer(LspServerConfig config, LspServerContext context, com.redhat.mcp.languagetools.workspace.Workspace workspace) {
        return new JdtLsServer(config, context, workspace);
    }
}
