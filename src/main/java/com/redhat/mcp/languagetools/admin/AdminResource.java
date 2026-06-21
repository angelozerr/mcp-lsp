package com.redhat.mcp.languagetools.admin;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

import com.redhat.mcp.languagetools.admin.dto.LspServerDTO;
import com.redhat.mcp.languagetools.admin.dto.WorkspaceDTO;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.workspace.WorkspaceManager;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    @Inject
    WorkspaceManager workspaceManager;

    @GET
    @Path("/workspaces")
    public List<WorkspaceDTO> listWorkspaces() {
        return workspaceManager.getWorkspaces().entrySet().stream()
                .map(entry -> toDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    @GET
    @Path("/workspaces/{uri}")
    public WorkspaceDTO getWorkspace(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = workspaceManager.getWorkspaces().get(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }
        return toDTO(uri, workspace);
    }

    /**
     * Close a workspace: shutdown all its LSP servers and remove from memory.
     */
    @DELETE
    @Path("/workspaces/{uri}")
    public Response closeWorkspace(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);

        workspaceManager.closeWorkspace(uri).join();

        return Response.ok()
                .entity("{\"status\": \"closed\", \"uri\": \"" + uri + "\"}")
                .build();
    }

    private WorkspaceDTO toDTO(URI uri, Workspace workspace) {
        // Get all available server descriptors
        var allServerConfigs = workspaceManager.getServerConfigs();

        // Map to DTOs with actual status (INSTALLING, STARTING, RUNNING, or STOPPED)
        List<LspServerDTO> servers = allServerConfigs.values().stream()
                .map(config -> {
                    LspServerDTO.ExternalInstanceInfo externalInfo = null;
                    Long pid = null;
                    String command = null;

                    // Only show external instance info if server is already connected
                    var lspServer = workspace.getLspServer(config.getId());
                    if (lspServer != null) {
                        var currentInstance = lspServer.getCurrentInstance();
                        if (currentInstance != null) {
                            externalInfo = new LspServerDTO.ExternalInstanceInfo(
                                currentInstance.port,
                                currentInstance.pid,
                                true,
                                currentInstance.clientName,
                                currentInstance.clientVersion
                            );
                        }

                        // Get PID and command from running server
                        pid = lspServer.getPid();
                        command = lspServer.getStartCommand();
                    }

                    return new LspServerDTO(
                        config.getId(),
                        config.getName(),
                        workspace.getServerStatus(config.getId()),
                        externalInfo,
                        pid,
                        command
                    );
                })
                .toList();

        // Build MCP client info with timestamps
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
        java.util.List<WorkspaceDTO.McpClientInfo> mcpClients = workspace.getMcpClientConnections().entrySet().stream()
                .map(entry -> new WorkspaceDTO.McpClientInfo(
                    entry.getKey(),
                    formatter.format(entry.getValue())
                ))
                .toList();

        LOG.infof("Workspace %s - mcpClients: %s", uri, mcpClients);
        return new WorkspaceDTO(uri, workspace.isInitialized(), mcpClients, servers);
    }
}
