/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.mcp.languagetools.lsp.client;

/**
 * LSP notification constants (client/* notifications from server to client).
 */
public class LspNotificationConstants {

    // client/* LSP notifications
    public static final String CLIENT_REGISTER_CAPABILITY = "client/registerCapability";
    public static final String CLIENT_UNREGISTER_CAPABILITY = "client/unregisterCapability";

    private LspNotificationConstants() {
    }
}
