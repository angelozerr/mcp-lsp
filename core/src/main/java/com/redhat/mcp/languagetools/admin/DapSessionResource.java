package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.admin.dto.CreateDapSessionRequest;
import com.redhat.mcp.languagetools.admin.dto.ErrorResponse;
import com.redhat.mcp.languagetools.dap.session.DapSession;
import com.redhat.mcp.languagetools.dap.session.DapSessionManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Map;

@Path("/api/admin/dap/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DapSessionResource {

    private static final Logger LOG = Logger.getLogger(DapSessionResource.class);

    @Inject
    DapSessionManager sessionManager;

    /**
     * Create a new DAP session for testing.
     */
    @POST
    public Response createSession(CreateDapSessionRequest request) {
        LOG.infof("Creating DAP session: workspace=%s, dapServerId=%s, name=%s",
            request.workspaceUri(), request.dapServerId(), request.sessionName());

        try {
            URI workspaceUri = URI.create(request.workspaceUri());

            DapSession session = sessionManager.createSession(
                workspaceUri,
                request.dapServerId(),
                request.sessionName()
            );

            // Return session info
            var response = Map.of(
                "sessionId", session.getSessionId(),
                "sessionName", session.getSessionName(),
                "dapServerId", session.getServerConfig().getId(),
                "state", session.getState().name(),
                "language", session.getLanguage()
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.fromException(e))
                .build();
        }
    }

    /**
     * Launch a DAP session with the provided configuration.
     */
    @POST
    @Path("/{sessionId}/launch")
    public Response launchSession(@PathParam("sessionId") String sessionId, Map<String, Object> launchConfig) {
        LOG.infof("Launching DAP session: sessionId=%s, config=%s", sessionId, launchConfig);

        try {
            DapSession session = sessionManager.getSession(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Session not found: " + sessionId))
                    .build();
            }

            // Launch the session (wait for completion)
            Map<String, Object> result = session.launch(launchConfig).join();

            return Response.ok(Map.of(
                "status", "launched",
                "sessionId", sessionId,
                "result", result
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to launch DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.fromException(e))
                .build();
        }
    }

    /**
     * Delete a DAP session.
     */
    @DELETE
    @Path("/{sessionId}")
    public Response deleteSession(@PathParam("sessionId") String sessionId) {
        LOG.infof("Deleting DAP session: sessionId=%s", sessionId);

        try {
            DapSession session = sessionManager.getSession(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Session not found: " + sessionId))
                    .build();
            }

            sessionManager.removeSession(sessionId);

            return Response.ok(Map.of(
                "status", "deleted",
                "sessionId", sessionId
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }
}
