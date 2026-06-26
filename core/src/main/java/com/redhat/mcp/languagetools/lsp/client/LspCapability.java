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
 * LSP capability enum.
 */
public enum LspCapability {

    REFERENCES(LspRequestConstants.TEXT_DOCUMENT_REFERENCES),
    DEFINITION(LspRequestConstants.TEXT_DOCUMENT_DEFINITION),
    DIAGNOSTIC(LspRequestConstants.TEXT_DOCUMENT_DIAGNOSTIC),
    HOVER(LspRequestConstants.TEXT_DOCUMENT_HOVER),
    COMPLETION(LspRequestConstants.TEXT_DOCUMENT_COMPLETION),
    DOCUMENT_SYMBOL(LspRequestConstants.TEXT_DOCUMENT_DOCUMENT_SYMBOL);

    private final String method;

    LspCapability(String method) {
        this.method = method;
    }

    public String getMethod() {
        return method;
    }
}
