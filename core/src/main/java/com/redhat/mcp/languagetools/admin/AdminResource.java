package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.admin.dto.DapServerDTO;
import com.redhat.mcp.languagetools.admin.dto.ServerDTOBuilder;
import com.redhat.mcp.languagetools.admin.dto.ServerRuntimeDTO;
import com.redhat.mcp.languagetools.admin.dto.WorkspaceDTO;
import com.redhat.mcp.languagetools.dap.server.DapServerConfig;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    @Inject
    ApplicationManager applicationManager;

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    @Inject
    com.redhat.mcp.languagetools.dap.session.DapSessionManager dapSessionManager;

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
        return applicationManager.getWorkspaces().entrySet().stream()
                .map(entry -> toDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    @GET
    @Path("/workspaces/{uri}")
    public WorkspaceDTO getWorkspace(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = applicationManager.getWorkspaces().get(uri);
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

        applicationManager.closeWorkspace(uri).join();

        return Response.ok()
                .entity("{\"status\": \"closed\", \"uri\": \"" + uri + "\"}")
                .build();
    }

    private WorkspaceDTO toDTO(URI uri, Workspace workspace) {
        // Get all available server descriptors
        var allServerConfigs = applicationManager.getLspServerConfigs();

        // Build runtime DTOs for all LSP servers in this workspace
        List<ServerRuntimeDTO> servers = allServerConfigs.values().stream()
                .map(config -> serverDTOBuilder.buildRuntime(config, workspace))
                .toList();

        // Build DAP server DTOs for this workspace
        List<DapServerDTO> dapServers = workspace.getDapServerConfigs().values().stream()
                .map(this::toDapDTO)
                .collect(Collectors.toList());

        // Build MCP client info with timestamps
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
        java.util.List<WorkspaceDTO.McpClientInfo> mcpClients = workspace.getMcpClientConnections().values().stream()
                .map(clientInfo -> new WorkspaceDTO.McpClientInfo(
                    clientInfo.name(),
                    formatter.format(clientInfo.connectedAt())
                ))
                .toList();

        // Build DAP session DTOs for this workspace
        List<WorkspaceDTO.DapSessionDTO> dapSessions = dapSessionManager.getAllSessions().stream()
                .filter(session -> session.getWorkspaceContext().getWorkspaceRoot().equals(uri))
                .map(session -> new WorkspaceDTO.DapSessionDTO(
                    session.getSessionId(),
                    session.getSessionName(),
                    session.getServerConfig().getId(),
                    session.getState().name(),
                    session.getLanguage()
                ))
                .collect(Collectors.toList());

        LOG.infof("Workspace %s - mcpClients: %s, dapServers: %d, dapSessions: %d",
            uri, mcpClients, dapServers.size(), dapSessions.size());
        // Note: dapServers are DAP configs for this workspace (from workspace.getDapServerConfigs())
        // They could be different per workspace, like LSP servers
        return new WorkspaceDTO(uri, workspace.isInitialized(), mcpClients, servers, dapServers, dapSessions);
    }

    /**
     * Get all available DAP (Debug Adapter Protocol) server configurations.
     */
    @GET
    @Path("/dap-servers")
    public List<DapServerDTO> listDapServers() {
        return applicationManager.getDapServerConfigs().values().stream()
                .map(this::toDapDTO)
                .collect(Collectors.toList());
    }

    private DapServerDTO toDapDTO(DapServerConfig config) {
        return new DapServerDTO(
            config.getId(),
            config.getName(),
            config.getDescription(),
            config.getDocumentSelector()
        );
    }
}
