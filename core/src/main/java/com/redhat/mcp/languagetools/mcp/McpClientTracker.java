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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Tracks the currently connected MCP client(s).
 */
@ApplicationScoped
public class McpClientTracker {

    private static final Logger LOG = Logger.getLogger(McpClientTracker.class);

    private volatile String currentClientName = "No client connected";
    private volatile String currentConnectionId = null;

    void onClientConnected(@Observes McpClientConnectedEvent event) {
        this.currentClientName = event.getClientName();
        this.currentConnectionId = event.getConnectionId();
        LOG.infof("Updated current MCP client: %s [%s]", currentClientName, currentConnectionId);
    }

    public String getCurrentClientName() {
        return currentClientName;
    }

    public String getCurrentConnectionId() {
        return currentConnectionId;
    }
}
