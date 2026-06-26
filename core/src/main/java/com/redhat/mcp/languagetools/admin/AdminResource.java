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
import com.redhat.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    @Inject
    WorkspaceManager workspaceManager;

    @GET
    @Path("/workspaces")
    public List<WorkspaceDTO> listWorkspaces() {
        return getCurrentWorkspaces();
    }

    /**
     * SSE stream REMOVED - use polling with GET /api/admin/workspaces instead.
     * SSE kept only for traces to avoid HTTP/1.1 connection pool exhaustion.
     * Event observers removed as they were only used for SSE broadcasting.
     */

    private List<WorkspaceDTO> getCurrentWorkspaces() {
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

                    // Get status message, truncate if too long
                    String statusMessage = lspServer != null ? lspServer.getStatusMessage() : null;
                    if (statusMessage != null && statusMessage.length() > 100) {
                        statusMessage = statusMessage.substring(0, 97) + "...";
                    }

                    // Get ready state
                    boolean isReady = lspServer != null && lspServer.isReady();

                    // Get contributesTo list (servers this one contributes to via bindRequest)
                    java.util.List<String> contributesTo = java.util.List.of();
                    if (config.getContributes() != null && config.getContributes().getContributions() != null) {
                        contributesTo = new java.util.ArrayList<>(config.getContributes().getContributions().keySet());
                    }

                    return new LspServerDTO(
                        config.getId(),
                        config.getName(),
                        workspace.getServerStatus(config.getId()),
                        statusMessage,
                        isReady,
                        contributesTo,
                        externalInfo,
                        pid,
                        command
                    );
                })
                .toList();

        // Build MCP client info with timestamps
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
        java.util.List<WorkspaceDTO.McpClientInfo> mcpClients = workspace.getMcpClientConnections().values().stream()
                .map(clientInfo -> new WorkspaceDTO.McpClientInfo(
                    clientInfo.name(),
                    formatter.format(clientInfo.connectedAt())
                ))
                .toList();

        LOG.infof("Workspace %s - mcpClients: %s", uri, mcpClients);
        return new WorkspaceDTO(uri, workspace.isInitialized(), mcpClients, servers);
    }
}
