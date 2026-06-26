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
package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.language.LanguageDocument;
import com.redhat.mcp.languagetools.workspace.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Resolves LSP servers for a given file with optional filtering.
 * Centralizes the logic for finding appropriate language servers.
 */
@ApplicationScoped
public class LspServerResolver {

    @Inject
    WorkspaceManager workspaceManager;

    /**
     * Get all LSP servers that can handle the given file and match the filter.
     *
     * @param document the language document
     * @param cwd      the current working directory (used for workspace detection)
     * @param filter   predicate to filter servers (e.g., by capability, enabled status)
     * @return completable future with list of matching servers
     */
    public CompletableFuture<List<LspServer>> getLspServersForFile(
            LanguageDocument document,
            String cwd,
            Predicate<LspServer> filter) {

        return workspaceManager.getWorkspaceForFile(document.getUri())
                .thenApply(workspace -> {
                    // Get all LSP servers from workspace
                    var allServers = workspace.getAllLspServers();

                    // Filter servers based on the predicate
                    return allServers.values().stream()
                            .filter(filter)
                            .collect(Collectors.toList());
                });
    }
}
