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
package com.redhat.mcp.languagetools.lsp.client.capabilities;

import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.language.LanguageDocument;
import com.redhat.mcp.languagetools.lsp.client.LspClientFeatures;
import com.redhat.mcp.languagetools.lsp.client.files.PathPatternMatcher;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Base class for Server capability registry for 'textDocument/*'.
 *
 * @param <T> the LSP {@link TextDocumentRegistrationOptions}.
 */
public abstract class TextDocumentServerCapabilityRegistry<T extends TextDocumentRegistrationOptions> {

    private final LspClientFeatures clientFeatures;
    private ServerCapabilities serverCapabilities;
    private final List<T> dynamicCapabilities;

    public TextDocumentServerCapabilityRegistry(LspClientFeatures clientFeatures) {
        this.clientFeatures = clientFeatures;
        this.dynamicCapabilities = new ArrayList<>();
    }

    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        this.serverCapabilities = serverCapabilities;
        this.dynamicCapabilities.clear();
    }

    public ServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    public T registerCapability(JsonObject registerOptions) {
        T t = create(registerOptions);
        if (t != null) {
            synchronized (dynamicCapabilities) {
                dynamicCapabilities.add(t);
            }
        }
        return t;
    }

    protected abstract T create(JsonObject registerOptions);

    public void unregisterCapability(Object options) {
        dynamicCapabilities.remove(options);
    }

    protected boolean isSupported(LanguageDocument document,
                                  Predicate<ServerCapabilities> matchServerCapabilities) {
        return isSupported(document, matchServerCapabilities, null);
    }

    protected boolean isSupported(LanguageDocument document,
                                  Predicate<ServerCapabilities> matchServerCapabilities,
                                  Predicate<T> matchOption) {
        // Check static server capabilities
        if (serverCapabilities != null && matchServerCapabilities.test(serverCapabilities)) {
            return true;
        }

        // Check dynamic capabilities
        if (dynamicCapabilities.isEmpty()) {
            return false;
        }

        String languageId = document.getLanguageId();
        String scheme = null;

        for (var option : dynamicCapabilities) {
            // Match documentSelector?
            var filters = ((ExtendedDocumentSelector.DocumentFilersProvider) option).getFilters();
            if (filters.isEmpty()) {
                return matchOption != null ? matchOption.test(option) : true;
            }

            for (var filter : filters) {
                boolean hasLanguage = filter.getLanguage() != null && !filter.getLanguage().isEmpty();
                boolean hasScheme = filter.getScheme() != null && !filter.getScheme().isEmpty();
                var pattern = filter.getPattern();
                boolean hasPattern = pattern != null && (pattern.isLeft() ? !pattern.getLeft().isEmpty() : true);

                boolean matchDocumentSelector = false;

                // Matches language?
                if (hasLanguage) {
                    matchDocumentSelector = (languageId == null && !hasScheme && !hasPattern)
                            || filter.getLanguage().equals(languageId);
                }

                if (!matchDocumentSelector) {
                    // Matches scheme?
                    if (hasScheme) {
                        if (scheme == null) {
                            scheme = document.getScheme();
                        }
                        matchDocumentSelector = filter.getScheme().equals(scheme);
                    }

                    if (!matchDocumentSelector) {
                        // Matches pattern?
                        if (hasPattern) {
                            PathPatternMatcher patternMatcher = filter.getPathPattern();
                            matchDocumentSelector = patternMatcher.matches(document.getUri());
                        }
                    }
                }

                if (matchDocumentSelector) {
                    if (matchOption == null) {
                        return true;
                    }
                    if (matchOption.test(option)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasCapability(final Either<Boolean, ?> eitherCapability) {
        if (eitherCapability == null) {
            return false;
        }
        return eitherCapability.isRight() || hasCapability(eitherCapability.getLeft());
    }

    public static boolean hasCapability(Boolean capability) {
        return capability != null && capability;
    }

    public List<T> getOptions() {
        return dynamicCapabilities;
    }

    protected LspClientFeatures getClientFeatures() {
        return clientFeatures;
    }
}
