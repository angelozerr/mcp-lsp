/*******************************************************************************
 * Copyright (c) 2026 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package com.redhat.mcp.languagetools.mcp;

import io.quarkiverse.mcp.server.InitialCheck;
import io.quarkiverse.mcp.server.InitialRequest;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import com.redhat.mcp.languagetools.mcp.McpClientConnectedEvent;

/**
 * MCP InitialCheck that captures and broadcasts client information.
 */
@ApplicationScoped
public class ClientInfoCheck implements InitialCheck {

    private static final Logger LOG = Logger.getLogger(ClientInfoCheck.class);

    @Inject
    Event<McpClientConnectedEvent> clientConnectedEvent;

    @Inject
    ConnectionManager connectionManager;

    @Override
    public Uni<CheckResult> perform(InitialRequest initialRequest) {
        LOG.infof("InitialCheck.perform() called");

        try {
            // Extract client info from MCP initialize request
            LOG.infof("Extracting client info from InitialRequest");
            LOG.infof("InitialRequest implementation: %s", initialRequest.implementation());

            String clientName = initialRequest.implementation().name();
            String clientVersion = initialRequest.implementation().version();
            String fullClientName = clientName + " " + clientVersion;

            LOG.infof("Extracted client name: %s, version: %s", clientName, clientVersion);

            // Find the connection ID by looking for the most recent connection
            // (the one that just triggered this check)
            String connectionId = findLatestConnectionId();

            LOG.infof("MCP client connected: %s [%s] (protocol: %s)",
                     fullClientName, connectionId, initialRequest.protocolVersion());

            // Fire CDI event with client info including connectionId
            clientConnectedEvent.fire(new McpClientConnectedEvent(fullClientName, connectionId));

            return InitialCheck.CheckResult.success();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract client info, allowing connection anyway");
            clientConnectedEvent.fire(new McpClientConnectedEvent("Unknown MCP Client", "unknown"));
            return InitialCheck.CheckResult.success();
        }
    }

    /**
     * Find the most recent connection ID from ConnectionManager.
     * Since InitialCheck is called during connection setup, the last connection
     * in the iterator is likely the one being initialized.
     */
    private String findLatestConnectionId() {
        String lastId = "unknown";
        int count = 0;
        for (var connection : connectionManager) {
            lastId = connection.id();
            count++;
            LOG.debugf("Found connection in manager: %s", lastId);
        }
        LOG.infof("ConnectionManager has %d connections, using latest: %s", count, lastId);
        return lastId;
    }
}

