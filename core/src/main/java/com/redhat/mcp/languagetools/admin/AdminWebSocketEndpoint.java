package com.redhat.mcp.languagetools.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.mcp.languagetools.admin.dto.DapServerDTO;
import com.redhat.mcp.languagetools.admin.dto.McpClientDTO;
import com.redhat.mcp.languagetools.admin.dto.ServerDTOBuilder;
import com.redhat.mcp.languagetools.admin.dto.ServerRuntimeDTO;
import com.redhat.mcp.languagetools.admin.dto.WorkspaceDTO;
import com.redhat.mcp.languagetools.admin.ws.*;
import com.redhat.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import com.redhat.mcp.languagetools.lsp.trace.LspTraceMessage;
import com.redhat.mcp.languagetools.mcp.trace.McpTrace;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.workspace.WorkspaceChangeEvent;
import com.redhat.mcp.languagetools.ApplicationManager;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.McpConnectionBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for real-time admin UI updates.
 * Replaces SSE streams and polling with a single bidirectional connection.
 */
@ServerEndpoint("/api/admin/ws")
@ApplicationScoped
public class AdminWebSocketEndpoint {

    private static final Logger LOG = Logger.getLogger(AdminWebSocketEndpoint.class);

    @Inject
    ApplicationManager applicationManager;

    @Inject
    ConnectionManager connectionManager;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.redhat.mcp.languagetools.lsp.trace.LspTraceCollector lspTraceCollector;

    @Inject
    com.redhat.mcp.languagetools.mcp.trace.McpTraceCollector mcpTraceCollector;

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    // Thread-safe set of active WebSocket sessions
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        LOG.infof("WebSocket client connected: %s (total: %d)", session.getId(), sessions.size());

        // Send initial state snapshot
        sendInitialState(session);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        sessions.remove(session);
        LOG.infof("WebSocket client disconnected: %s, reason: %s (remaining: %d)",
                session.getId(), closeReason, sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        LOG.errorf(throwable, "WebSocket error for session: %s", session.getId());
        sessions.remove(session);
    }

    /**
     * Send initial state when client connects.
     */
    private void sendInitialState(Session session) {
        try {
            // Send current workspaces
            WorkspacesUpdateWsMessage workspacesMsg = new WorkspacesUpdateWsMessage(
                    "workspaces-update",
                    getCurrentWorkspaces()
            );
            sendToSession(session, workspacesMsg);

            // Send current MCP clients
            McpClientsUpdateWsMessage clientsMsg = new McpClientsUpdateWsMessage(
                    "mcp-clients-update",
                    getCurrentMcpClients()
            );
            sendToSession(session, clientsMsg);

            // Send LSP trace history for all servers
            sendLspTraceHistory(session);

            // Send MCP trace history
            sendMcpTraceHistory(session);

            LOG.debugf("Initial state sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send initial state to session: %s", session.getId());
        }
    }

    /**
     * Send LSP trace history for all servers.
     */
    private void sendLspTraceHistory(Session session) {
        try {
            // Get all workspaces and their servers
            for (var workspace : applicationManager.getWorkspaces().values()) {
                for (var serverId : workspace.getAllLspServers().keySet()) {
                    // Get last 200 traces for this server
                    var traces = lspTraceCollector.getTracesForWorkspaceAndServer(
                        workspace.getRootUri().toString(),
                        serverId,
                        200
                    );

                    // Send each trace
                    for (var trace : traces) {
                        LspTraceWsMessage msg = new LspTraceWsMessage(
                            "lsp-trace",
                            trace.workspaceUri().toString(),
                            trace.serverId(),
                            trace.serverName(),
                            trace.timestamp().toString(),
                            trace.direction().name(),
                            trace.jsonContent()
                        );
                        sendToSession(session, msg);
                    }
                }
            }
            LOG.debugf("LSP trace history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send LSP trace history to session: %s", session.getId());
        }
    }

    /**
     * Send MCP trace history.
     */
    private void sendMcpTraceHistory(Session session) {
        try {
            // Get last 500 MCP traces
            var traces = mcpTraceCollector.getTraces(500);

            // Send each trace
            for (var trace : traces) {
                McpTraceWsMessage msg = new McpTraceWsMessage(
                    "mcp-trace",
                    trace.direction(),
                    trace.connectionId(),
                    trace.message(),
                    trace.timestamp().toString()
                );
                sendToSession(session, msg);
            }
            LOG.debugf("MCP trace history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send MCP trace history to session: %s", session.getId());
        }
    }

    /**
     * CDI observer for LSP trace events.
     */
    void onLspTrace(@Observes LspTraceMessage trace) {
        LspTraceWsMessage msg = new LspTraceWsMessage(
                "lsp-trace",
                trace.workspaceUri().toString(),
                trace.serverId(),
                trace.serverName(),
                trace.timestamp().toString(),
                trace.direction().name(),
                trace.jsonContent()
        );
        broadcast(msg);
    }

    /**
     * CDI observer for MCP trace events.
     */
    void onMcpTrace(@Observes McpTrace trace) {
        McpTraceWsMessage msg = new McpTraceWsMessage(
                "mcp-trace",
                trace.direction(),
                trace.connectionId(),
                trace.message(),
                trace.timestamp().toString()
        );
        broadcast(msg);
    }

    /**
     * CDI observer for DAP trace events.
     */
    void onDapTrace(@Observes com.redhat.mcp.languagetools.dap.trace.DapTraceMessage trace) {
        com.redhat.mcp.languagetools.admin.ws.DapTraceWsMessage msg = new com.redhat.mcp.languagetools.admin.ws.DapTraceWsMessage(
                "dap-trace",
                trace.workspaceUri(),
                trace.sessionId(),
                trace.sessionName(),
                trace.timestamp().toString(),
                trace.direction().name(),
                trace.jsonContent()
        );
        broadcast(msg);
    }

    /**
     * CDI observer for workspace changes (created/closed).
     */
    void onWorkspaceChange(@Observes WorkspaceChangeEvent event) {
        LOG.infof("Workspace changed: %s - %s", event.type(), event.workspaceUri());

        // Send full workspace list (simpler than delta updates)
        WorkspacesUpdateWsMessage msg = new WorkspacesUpdateWsMessage(
                "workspaces-update",
                getCurrentWorkspaces()
        );
        broadcast(msg);

        // Also send updated MCP clients list (tied to workspaces)
        McpClientsUpdateWsMessage clientsMsg = new McpClientsUpdateWsMessage(
                "mcp-clients-update",
                getCurrentMcpClients()
        );
        broadcast(clientsMsg);
    }

    /**
     * CDI observer for server status changes.
     */
    void onServerStatusChange(@Observes LspServerStatusChangeEvent event) {
        LOG.infof("WebSocket: Server status changed: %s/%s - %s -> %s (broadcasting to %d clients)",
                event.workspaceUri(), event.serverId(), event.oldStatus(), event.newStatus(), sessions.size());

        // Send status change event
        ServerStatusChangedWsMessage msg = new ServerStatusChangedWsMessage(
                "server-status-changed",
                event.workspaceUri().toString(),
                event.serverId(),
                event.oldStatus().name(),
                event.newStatus().name()
        );
        broadcast(msg);

        // Also send full workspace list to keep UI in sync
        WorkspacesUpdateWsMessage workspacesMsg = new WorkspacesUpdateWsMessage(
                "workspaces-update",
                getCurrentWorkspaces()
        );
        broadcast(workspacesMsg);
    }

    /**
     * Broadcast message to all connected sessions.
     */
    private void broadcast(Object message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize message: %s", message);
            return;
        }

        sessions.forEach(session -> {
            if (session.isOpen()) {
                // Use async send with callback for backpressure handling
                session.getAsyncRemote().sendText(json, result -> {
                    if (!result.isOK()) {
                        LOG.warnf("Failed to send to session %s: %s",
                                session.getId(), result.getException().getMessage());

                        // Close slow clients that can't keep up
                        try {
                            session.close(new CloseReason(
                                    CloseReason.CloseCodes.TRY_AGAIN_LATER,
                                    "Client too slow"
                            ));
                        } catch (IOException e) {
                            // Session already closed
                        }
                        sessions.remove(session);
                    }
                });
            } else {
                sessions.remove(session);
            }
        });
    }

    /**
     * Send message to a specific session.
     */
    private void sendToSession(Session session, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.getAsyncRemote().sendText(json);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send message to session: %s", session.getId());
        }
    }

    /**
     * Get current workspaces (copied from AdminResource logic).
     */
    private List<WorkspaceDTO> getCurrentWorkspaces() {
        return applicationManager.getWorkspaces().entrySet().stream()
                .map(entry -> toWorkspaceDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Get current MCP clients (copied from McpClientsResource logic).
     */
    private List<McpClientDTO> getCurrentMcpClients() {
        List<McpClientDTO> clients = new ArrayList<>();

        for (McpConnectionBase connection : connectionManager) {
            var initialRequest = connection.initialRequest();

            String name = "Unknown";
            String version = null;
            String protocolVersion = null;

            if (initialRequest != null) {
                if (initialRequest.implementation() != null) {
                    name = initialRequest.implementation().name();
                    version = initialRequest.implementation().version();
                }
                protocolVersion = initialRequest.protocolVersion();
            }

            clients.add(new McpClientDTO(
                    connection.id(),
                    name,
                    version,
                    protocolVersion,
                    null  // connectedAt not persisted
            ));
        }

        return clients;
    }

    /**
     * Convert workspace to DTO (copied from AdminResource).
     */
    private WorkspaceDTO toWorkspaceDTO(URI uri, Workspace workspace) {
        var allServerConfigs = applicationManager.getLspServerConfigs();

        // Build runtime DTOs for all LSP servers in this workspace
        List<ServerRuntimeDTO> servers = allServerConfigs.values().stream()
                .map(config -> serverDTOBuilder.buildRuntime(config, workspace))
                .toList();

        // Build DAP server DTOs for this workspace
        List<DapServerDTO> dapServers = workspace.getDapServerConfigs().values().stream()
                .map(config -> new DapServerDTO(
                    config.getId(),
                    config.getName(),
                    config.getDescription(),
                    config.getDocumentSelector()
                ))
                .toList();

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
        java.util.List<WorkspaceDTO.McpClientInfo> mcpClients = workspace.getMcpClientConnections().values().stream()
                .map(clientInfo -> new WorkspaceDTO.McpClientInfo(
                        clientInfo.name(),
                        formatter.format(clientInfo.connectedAt())
                ))
                .toList();

        // Build DAP session DTOs for this workspace (empty list for now, updated via AdminResource)
        java.util.List<WorkspaceDTO.DapSessionDTO> dapSessions = java.util.Collections.emptyList();

        return new WorkspaceDTO(uri, workspace.isInitialized(), mcpClients, servers, dapServers, dapSessions);
    }
}
