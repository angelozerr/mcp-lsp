package com.redhat.mcp.languagetools.tools;

import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools for workspace management.
 */
@ApplicationScoped
public class WorkspaceTools {

    private static final Logger LOG = Logger.getLogger(WorkspaceTools.class);

    @Inject
    ApplicationManager applicationManager;

    @Tool(description = "Get information about all active workspaces, including root URIs and language server count. " +
                        "Workspaces are initialized automatically when using diagnostics tools.")
    public String listWorkspaces() {
        try {
            Map<URI, Workspace> workspaces = applicationManager.getWorkspaces();

            if (workspaces.isEmpty()) {
                return "No workspaces currently active";
            }

            return workspaces.entrySet().stream()
                    .map(entry -> String.format("- %s (%d language servers, initialized: %s)",
                            entry.getKey(),
                            entry.getValue().getAllLspServers().size(),
                            entry.getValue().isInitialized()))
                    .collect(Collectors.joining("\n", "Active workspaces:\n", ""));

        } catch (Exception e) {
            LOG.error("Failed to list workspaces", e);
            return "Failed to list workspaces: " + e.getMessage();
        }
    }

    @Tool(description = "Get information about configured language servers (ID, name, description)")
    public String listLanguageServers() {
        try {
            var servers = applicationManager.getLspServerConfigs();

            if (servers.isEmpty()) {
                return "No language servers configured";
            }

            return servers.values().stream()
                    .map(config -> String.format("- %s (%s): %s",
                            config.getId(),
                            config.getName(),
                            config.getDescription() != null ? config.getDescription() : "No description"))
                    .collect(Collectors.joining("\n", "Configured language servers:\n", ""));

        } catch (Exception e) {
            LOG.error("Failed to list language servers", e);
            return "Failed to list language servers: " + e.getMessage();
        }
    }
}
