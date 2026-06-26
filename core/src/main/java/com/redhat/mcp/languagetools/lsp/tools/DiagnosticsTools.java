package com.redhat.mcp.languagetools.lsp.tools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.Diagnostic;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.annotations.RequireDidOpen;
import com.redhat.mcp.languagetools.tools.ToolArgDescriptions;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.workspace.WorkspaceManager;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for LSP diagnostics.
 */
@ApplicationScoped
public class DiagnosticsTools {

    private static final Logger LOG = Logger.getLogger(DiagnosticsTools.class);

    @Inject
    WorkspaceManager workspaceManager;

    @Tool(description = "Get diagnostics (errors, warnings) for a file from all language servers. " +
                        "The workspace is auto-detected and initialized if needed. " +
                        "The file will be automatically opened if needed to get fresh diagnostics. " +
                        "Example: getDiagnostics(cwd='/home/user/projects/my-app', fileUri='file:///home/user/projects/my-app/src/Main.java')")
    @RequireDidOpen(uriParam = "fileUri")
    public String getDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri) {
        try {
            URI uri = URI.create(fileUri);
            LOG.infof("Getting diagnostics for: %s (from cwd: %s)", uri, cwd);

            Workspace ws = workspaceManager.getWorkspaceForFile(uri).join();

            StringBuilder result = new StringBuilder();
            result.append("Diagnostics for: ").append(uri).append("\n\n");

            Map<String, LspServer> servers = ws.getAllLspServers();
            if (servers.isEmpty()) {
                return "No language servers available in workspace";
            }

            boolean foundDiagnostics = false;

            for (Map.Entry<String, LspServer> entry : servers.entrySet()) {
                String serverId = entry.getKey();
                LspServer server = entry.getValue();

                List<Diagnostic> diagnostics = server.getDiagnosticsCache().get(fileUri);

                if (diagnostics != null && !diagnostics.isEmpty()) {
                    foundDiagnostics = true;
                    result.append(String.format("Language Server: %s (%s)\n",
                            serverId, server.getConfig().getName()));

                    for (Diagnostic diagnostic : diagnostics) {
                        result.append(String.format("  [%s] Line %d: %s\n",
                                diagnostic.getSeverity(),
                                diagnostic.getRange().getStart().getLine() + 1,
                                diagnostic.getMessage()));
                    }
                    result.append("\n");
                }
            }

            if (!foundDiagnostics) {
                return "No diagnostics found for: " + uri;
            }

            return result.toString();

        } catch (Exception e) {
            LOG.error("Failed to get diagnostics", e);
            return "Failed to get diagnostics: " + e.getMessage();
        }
    }

    @Tool(description = "Get all diagnostics from all files in a workspace. " +
                        "The workspace is auto-detected and initialized if needed. " +
                        "Example: getAllDiagnostics(cwd='/home/user/projects/my-app')")
    public String getAllDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            if (cwd == null || cwd.isEmpty()) {
                return "Error: cwd must be provided";
            }

            URI uri = new File(cwd).toURI();
            LOG.infof("Getting all diagnostics for workspace: %s", uri);

            Workspace ws = workspaceManager.getOrCreateWorkspace(uri);

            StringBuilder result = new StringBuilder();
            result.append("All diagnostics for workspace: ").append(uri).append("\n\n");

            Map<String, LspServer> servers = ws.getAllLspServers();
            if (servers.isEmpty()) {
                return "No language servers available in workspace";
            }

            boolean foundDiagnostics = false;

            for (Map.Entry<String, LspServer> entry : servers.entrySet()) {
                String serverId = entry.getKey();
                LspServer server = entry.getValue();

                Map<String, List<Diagnostic>> allDiagnostics = server.getDiagnosticsCache();

                if (!allDiagnostics.isEmpty()) {
                    foundDiagnostics = true;
                    result.append(String.format("Language Server: %s (%s)\n",
                            serverId, server.getConfig().getName()));

                    for (Map.Entry<String, List<Diagnostic>> fileEntry : allDiagnostics.entrySet()) {
                        String fileUri = fileEntry.getKey();
                        List<Diagnostic> diagnostics = fileEntry.getValue();

                        if (!diagnostics.isEmpty()) {
                            result.append(String.format("\n  File: %s\n", fileUri));
                            for (Diagnostic diagnostic : diagnostics) {
                                result.append(String.format("    [%s] Line %d: %s\n",
                                        diagnostic.getSeverity(),
                                        diagnostic.getRange().getStart().getLine() + 1,
                                        diagnostic.getMessage()));
                            }
                        }
                    }
                    result.append("\n");
                }
            }

            if (!foundDiagnostics) {
                return "No diagnostics found in workspace";
            }

            return result.toString();

        } catch (Exception e) {
            LOG.error("Failed to get all diagnostics", e);
            return "Failed to get all diagnostics: " + e.getMessage();
        }
    }

}
