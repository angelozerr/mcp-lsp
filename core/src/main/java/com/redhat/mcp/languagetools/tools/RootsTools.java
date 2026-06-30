package com.redhat.mcp.languagetools.tools;

import io.quarkiverse.mcp.server.Root;
import io.quarkiverse.mcp.server.Roots;
import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;

import java.net.URI;
import java.util.List;

/**
 * MCP tools for working with client roots.
 *
 * NOTE: MCP Roots are DEPRECATED in the latest spec draft.
 * Migration path: use cwd parameter in tools instead.
 * This class is kept for backward compatibility.
 */
@ApplicationScoped
public class RootsTools {

    private static final Logger LOG = Logger.getLogger(RootsTools.class);

    @Inject
    ApplicationManager applicationManager;

    @Tool(description = "List all workspace roots provided by the MCP client. " +
                        "NOTE: This feature is deprecated, prefer using cwd parameter in other tools.")
    public String listRoots(Roots roots) {
        if (!roots.isSupported()) {
            return "MCP Roots not supported by this client. Use cwd parameter instead.";
        }

        try {
            List<Root> rootList = roots.listAndAwait();

            if (rootList.isEmpty()) {
                return "No workspace roots configured in client";
            }

            StringBuilder result = new StringBuilder("Client workspace roots:\n");
            for (Root root : rootList) {
                result.append(String.format("- %s: %s\n",
                    root.name() != null ? root.name() : "Unnamed",
                    root.uri()));
            }

            return result.toString();

        } catch (Exception e) {
            LOG.error("Failed to list roots", e);
            return "Failed to list roots: " + e.getMessage();
        }
    }

    @Tool(description = "Initialize all workspaces from MCP client roots. " +
                        "This will start language servers for each root directory. " +
                        "NOTE: This feature is deprecated, prefer using initialize_workspace with cwd parameter.")
    public String initializeAllRoots(Roots roots) {
        if (!roots.isSupported()) {
            return "MCP Roots not supported by this client. Use initialize_workspace with cwd parameter instead.";
        }

        try {
            List<Root> rootList = roots.listAndAwait();

            if (rootList.isEmpty()) {
                return "No workspace roots to initialize";
            }

            StringBuilder result = new StringBuilder("Initializing workspaces from roots:\n\n");
            int successCount = 0;
            int errorCount = 0;

            for (Root root : rootList) {
                try {
                    URI rootUri = URI.create(root.uri());
                    LOG.infof("Initializing workspace: %s (%s)", root.name(), rootUri);

                    Workspace workspace = applicationManager.getOrCreateWorkspace(rootUri);

                    result.append(String.format("✓ %s: initialized with %d language servers\n",
                        root.name() != null ? root.name() : rootUri.toString(),
                        workspace.getAllLspServers().size()));
                    successCount++;

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to initialize workspace: %s", root.uri());
                    result.append(String.format("✗ %s: %s\n",
                        root.name() != null ? root.name() : root.uri(),
                        e.getMessage()));
                    errorCount++;
                }
            }

            result.append(String.format("\nSummary: %d initialized, %d errors", successCount, errorCount));
            return result.toString();

        } catch (Exception e) {
            LOG.error("Failed to initialize roots", e);
            return "Failed to initialize roots: " + e.getMessage();
        }
    }
}
