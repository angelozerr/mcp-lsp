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
package com.redhat.mcp.languagetools.language;

import java.net.URI;

/**
 * Represents a document with its URI and detected language.
 * The languageId can be null if the language cannot be detected.
 */
public class LanguageDocument {

    private final URI uri;
    private final String languageId;

    public LanguageDocument(URI uri, String languageId) {
        this.uri = uri;
        this.languageId = languageId;
    }

    public URI getUri() {
        return uri;
    }

    public String getLanguageId() {
        return languageId;
    }

    /**
     * Get the URI scheme (e.g., "file", "http").
     * Delegates to the underlying URI.
     */
    public String getScheme() {
        return uri.getScheme();
    }
}
