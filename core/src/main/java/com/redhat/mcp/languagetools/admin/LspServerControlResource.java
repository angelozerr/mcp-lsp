package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.admin.dto.ErrorResponse;
import com.redhat.mcp.languagetools.admin.dto.ServerConfigDTO;
import com.redhat.mcp.languagetools.admin.dto.ServerDTOBuilder;
import com.redhat.mcp.languagetools.admin.dto.StatusResponse;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

@Path("/api/admin/servers")
@Produces(MediaType.APPLICATION_JSON)
public class LspServerControlResource {

    private static final Logger LOG = Logger.getLogger(LspServerControlResource.class);

    @Inject
    ApplicationManager applicationManager;

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    /**
     * List all configured servers (static config, independent of workspaces).
     * Returns server configurations without runtime state.
     */
    @GET
    public List<ServerConfigDTO> listAllServers() {
        LOG.info("listAllServers() called");
        try {
            var configs = applicationManager.getLspServerConfigs();
            LOG.infof("Found %d server configs", configs.size());

            var result = configs.values().stream()
                    .map(serverDTOBuilder::buildConfig)
                    .toList();

            LOG.infof("Returning %d servers", result.size());
            return result;
        } catch (Exception e) {
            LOG.error("Error in listAllServers", e);
            throw e;
        }
    }

    @POST
    @Path("/{workspaceUri}/{serverId}/stop")
    public Response stopServer(@PathParam("workspaceUri") String workspaceUriParam,
                                @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity("Workspace not found").build();
            }

            var server = workspace.getAllLspServers().get(serverId);
            if (server == null) {
                return Response.status(404).entity("Server not found").build();
            }

            server.shutdown().join();
            return Response.ok().entity(new StatusResponse("stopped")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{workspaceUri}/{serverId}/restart")
    public Response restartServer(@PathParam("workspaceUri") String workspaceUriParam,
                                   @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity("Workspace not found").build();
            }

            // Restart via workspace (recreates server instance, will detect IDE if available)
            workspace.restartLspServer(serverId).join();

            return Response.ok().entity(new StatusResponse("restarted")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{workspaceUri}/{serverId}/start-managed")
    public Response startManagedServer(@PathParam("workspaceUri") String workspaceUriParam,
                                        @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity("Workspace not found").build();
            }

            // Check if server already exists
            var existingServer = workspace.getLspServer(serverId);
            if (existingServer != null) {
                // Server exists, just start it
                workspace.startManagedLspServer(serverId).join();
            } else {
                // Server doesn't exist, need to install and add it first
                applicationManager.ensureServerInstalled(serverId, workspaceUri).join();
            }

            return Response.ok().entity(new StatusResponse("started")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{workspaceUri}/{serverId}/disconnect")
    public Response disconnectFromIde(@PathParam("workspaceUri") String workspaceUriParam,
                                       @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity("Workspace not found").build();
            }

            var server = workspace.getAllLspServers().get(serverId);
            if (server == null) {
                return Response.status(404).entity("Server not found").build();
            }

            // Just shutdown the connection (don't remove the server)
            server.shutdown().join();
            return Response.ok().entity(new StatusResponse("disconnected")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{workspaceUri}/{serverId}/connect-ide")
    public Response connectToIde(@PathParam("workspaceUri") String workspaceUriParam,
                                  @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = applicationManager.getWorkspaces().get(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity("Workspace not found").build();
            }

            // Check if IDE instance is available
            var externalInstance = workspace.getExternalInstance(serverId);
            if (externalInstance == null) {
                return Response.status(404).entity("No IDE instance available for this server").build();
            }

            // Restart will detect and connect to the IDE instance
            workspace.restartLspServer(serverId).join();

            return Response.ok().entity(new StatusResponse("connected")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }
}
