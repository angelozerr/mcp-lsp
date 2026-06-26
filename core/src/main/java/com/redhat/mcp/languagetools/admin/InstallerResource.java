package com.redhat.mcp.languagetools.admin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.admin.dto.ErrorResponse;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;
import com.redhat.mcp.languagetools.lsp.installer.task.InstallerTask;
import com.redhat.mcp.languagetools.lsp.installer.task.InstallerTaskRegistry;
import com.redhat.mcp.languagetools.lsp.server.ServerStatus;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector;
import com.redhat.mcp.languagetools.workspace.WorkspaceManager;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;

/**
 * REST endpoints for running installers.
 */
@Path("/api/admin/install")
@Produces(MediaType.APPLICATION_JSON)
public class InstallerResource {

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    LspTraceCollector traceCollector;

    @Inject
    PathManager pathManager;

    /**
     * Load installer.json from user config directory first, then fallback to bundled resources.
     */
    private JsonObject loadInstallerJson(String serverId, Gson gson) throws Exception {
        // Try user config directory first
        var userConfigPath = pathManager.getServerInstallerConfig(serverId);

        if (Files.exists(userConfigPath)) {
            String content = Files.readString(userConfigPath);
            return gson.fromJson(content, JsonObject.class);
        }

        // Fallback to bundled resources
        String installerPath = "/lsp/" + serverId + "/installer.json";
        InputStream is = getClass().getResourceAsStream(installerPath);
        if (is == null) {
            return null;
        }

        return gson.fromJson(new InputStreamReader(is), JsonObject.class);
    }

    @POST
    @Path("/{workspaceUri}/{serverId}")
    public Response runInstaller(@PathParam("workspaceUri") String workspaceUriParam,
                                  @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);

            // Get server config
            LspServerConfig config = workspaceManager.getServerConfigs().get(serverId);
            if (config == null) {
                return Response.status(404).entity(new ErrorResponse("Server config not found")).build();
            }

            // Load installer.json (user config or bundled)
            Gson gson = new Gson();
            JsonObject installerJson = loadInstallerJson(serverId, gson);
            if (installerJson == null) {
                return Response.status(404).entity(new ErrorResponse("No installer.json found for " + serverId)).build();
            }

            // Get or create workspace to update status
            var workspace = workspaceManager.getOrCreateWorkspace(workspaceUri);

            // Set status to INSTALLING
            workspace.setInstallationStatus(serverId, ServerStatus.INSTALLING);

            // Create installer context
            InstallerContext context = new InstallerContext();
            context.setProperty("workspace", workspaceUri.toString());
            context.setProperty("server.id", serverId);
            context.setProperty("server.home", pathManager.getServerHome(serverId).toString());
            context.setTraceCollector(traceCollector, workspaceUri.toString(), serverId, config.getName());

            // Create task registry and load tasks
            InstallerTaskRegistry registry = new InstallerTaskRegistry();

            // Execute check task if present
            if (installerJson.has("check")) {
                JsonObject checkJson = installerJson.getAsJsonObject("check");
                InstallerTask checkTask = registry.loadTask(checkJson);

                context.log("=== Running check phase ===");
                boolean checkResult = checkTask.execute(context);

                if (checkResult) {
                    // Clear installation status - already installed
                    workspace.setInstallationStatus(serverId, null);

                    return Response.ok()
                            .entity("{\"status\": \"already_installed\", \"message\": \"Server already installed\"}")
                            .build();
                }
            }

            // Execute run task if present
            if (installerJson.has("run")) {
                JsonObject runJson = installerJson.getAsJsonObject("run");
                InstallerTask runTask = registry.loadTask(runJson);

                context.log("=== Running installation phase ===");

                try {
                    boolean runResult = runTask.execute(context);

                    if (runResult) {
                        // Clear installation status - success
                        workspace.setInstallationStatus(serverId, null);

                        String serverHome = context.getPropertyAsString("output.dir");
                        return Response.ok()
                                .entity("{\"status\": \"installed\", \"message\": \"Installation successful\", \"serverHome\": \"" + serverHome + "\"}")
                                .build();
                    } else {
                        // Set INSTALL_FAILED status
                        workspace.setInstallationStatus(serverId, ServerStatus.INSTALL_FAILED);

                        // Get error log from context
                        String errorLog = context.getErrorLog();
                        String errorDetails = errorLog.isEmpty() ? "No error details captured" : errorLog;

                        return Response.status(500)
                                .entity("{\"status\": \"failed\", \"message\": \"Installation task failed\", \"error\": \"" +
                                        errorDetails.replace("\"", "\\\"").replace("\n", "\\n") + "\"}")
                                .build();
                    }
                } catch (Exception e) {
                    // Set INSTALL_FAILED status
                    workspace.setInstallationStatus(serverId, ServerStatus.INSTALL_FAILED);

                    String errorDetails = e.getClass().getSimpleName() + ": " + e.getMessage();
                    if (e.getCause() != null) {
                        errorDetails += " (Caused by: " + e.getCause().getMessage() + ")";
                    }

                    return Response.status(500)
                            .entity("{\"status\": \"failed\", \"message\": \"Installation exception\", \"error\": \"" +
                                    errorDetails.replace("\"", "\\\"") + "\"}")
                            .build();
                }
            }

            return Response.status(400)
                    .entity(new ErrorResponse("No run task defined in installer.json"))
                    .build();

        } catch (Exception e) {
            return Response.status(500)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
}
