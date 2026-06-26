package com.redhat.mcp.languagetools.admin;

import com.redhat.mcp.languagetools.config.GlobalConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoint for global configuration.
 */
@Path("/api/admin/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Inject
    GlobalConfiguration globalConfig;

    /**
     * Get trace level for a server.
     */
    @GET
    @Path("/servers/{serverId}/trace")
    public Response getTraceLevel(@PathParam("serverId") String serverId) {
        String level = globalConfig.getServerTraceLevel(serverId);
        return Response.ok()
                .entity("{\"serverId\": \"" + serverId + "\", \"trace\": \"" + level + "\"}")
                .build();
    }

    /**
     * Set trace level for a server.
     */
    @PUT
    @Path("/servers/{serverId}/trace")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setTraceLevel(@PathParam("serverId") String serverId, String body) {
        try {
            // Parse {"trace": "verbose"}
            String trace = com.google.gson.JsonParser.parseString(body)
                    .getAsJsonObject()
                    .get("trace")
                    .getAsString();

            // Validate
            if (!trace.equals("off") && !trace.equals("messages") && !trace.equals("verbose")) {
                return Response.status(400)
                        .entity("{\"error\": \"Invalid trace level. Must be: off, messages, or verbose\"}")
                        .build();
            }

            globalConfig.setServerTraceLevel(serverId, trace);

            return Response.ok()
                    .entity("{\"serverId\": \"" + serverId + "\", \"trace\": \"" + trace + "\"}")
                    .build();

        } catch (Exception e) {
            return Response.status(400)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Get MCP trace level.
     */
    @GET
    @Path("/mcp/trace")
    public Response getMcpTraceLevel() {
        String level = globalConfig.getMcpTraceLevel();
        return Response.ok()
                .entity("{\"trace\": \"" + level + "\"}")
                .build();
    }

    /**
     * Set MCP trace level.
     */
    @PUT
    @Path("/mcp/trace")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setMcpTraceLevel(String body) {
        try {
            String trace = com.google.gson.JsonParser.parseString(body)
                    .getAsJsonObject()
                    .get("trace")
                    .getAsString();

            // Validate
            if (!trace.equals("off") && !trace.equals("messages") && !trace.equals("verbose")) {
                return Response.status(400)
                        .entity("{\"error\": \"Invalid trace level. Must be: off, messages, or verbose\"}")
                        .build();
            }

            globalConfig.setMcpTraceLevel(trace);

            return Response.ok()
                    .entity("{\"trace\": \"" + trace + "\"}")
                    .build();

        } catch (Exception e) {
            return Response.status(400)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
