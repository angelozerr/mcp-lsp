package com.redhat.mcp.languagetools.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.admin.dto.ErrorResponse;
import com.redhat.mcp.languagetools.admin.dto.ServerConfigDTO;
import com.redhat.mcp.languagetools.admin.dto.ServerDTOBuilder;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.ApplicationManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.nio.file.Files;

/**
 * REST endpoint for server details and installer configuration.
 */
@Path("/api/admin/servers")
@Produces(MediaType.APPLICATION_JSON)
public class ServerDetailsResource {

    @Inject
    ApplicationManager applicationManager;

    @Inject
    PathManager pathManager;

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @GET
    @Path("/{serverId}/details")
    public ServerConfigDTO getServerDetails(@PathParam("serverId") String serverId) {
        LspServerConfig config = applicationManager.getLspServerConfigs().get(serverId);

        if (config == null) {
            throw new NotFoundException("Server not found: " + serverId);
        }

        return serverDTOBuilder.buildConfig(config);
    }

    @GET
    @Path("/{serverId}/installer")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInstallerJson(@PathParam("serverId") String serverId) {
        // Try user config directory first
        var userConfigPath = pathManager.getServerInstallerConfig(serverId);

        try {
            String jsonContent;

            if (java.nio.file.Files.exists(userConfigPath)) {
                jsonContent = java.nio.file.Files.readString(userConfigPath);
            } else {
                // Fallback to bundled resources
                String installerPath = "/lsp/" + serverId + "/installer.json";
                InputStream is = getClass().getResourceAsStream(installerPath);
                if (is == null) {
                    return Response.status(404).entity(new ErrorResponse("No installer.json found")).build();
                }
                jsonContent = new String(is.readAllBytes());
            }

            return Response.ok(jsonContent, MediaType.APPLICATION_JSON).build();

        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{serverId}/installer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveInstallerJson(@PathParam("serverId") String serverId, String jsonContent) {
        try {
            // Validate JSON
            JsonObject installerJson = gson.fromJson(jsonContent, JsonObject.class);

            // Save to user config directory (not bundled resources)
            var configDir = pathManager.getServerConfigDir(serverId);
            Files.createDirectories(configDir);

            var installerFile = configDir.resolve("installer.json");
            Files.writeString(installerFile, gson.toJson(installerJson));

            return Response.ok()
                    .entity("{\"status\": \"saved\", \"path\": \"" + installerFile.toString().replace("\\", "\\\\") + "\"}")
                    .build();

        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }
}
