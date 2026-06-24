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
package com.redhat.mcp.languagetools.lsp.tools;

import com.redhat.mcp.languagetools.language.LanguageRegistry;
import com.redhat.mcp.languagetools.lsp.client.LspCapability;
import com.redhat.mcp.languagetools.lsp.server.LspServerResolver;
import com.redhat.mcp.languagetools.mcp.McpCancellationSupport;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.*;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.tools.ToolArgDescriptions;
import com.redhat.mcp.languagetools.workspace.WorkspaceManager;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * MCP tools for LSP references (find all references).
 */
@ApplicationScoped
public class ReferencesTools {

    private static final Logger LOG = Logger.getLogger(ReferencesTools.class);

    @Inject
    LspServerResolver serverResolver;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    McpCancellationSupport cancellationSupport;

    @Tool(description = "Find all references to a symbol at a specific position in a file. " +
                        "Returns all locations where the symbol is used across the workspace. " +
                        "Example: findReferences(cwd='/home/user/project', fileUri='file:///home/user/project/src/Main.java', line=10, character=5)")
    public CompletableFuture<String> findReferences(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation) {
        // Create language document (detects language once)
        var document = languageRegistry.createDocument(fileUri);

        // Get all servers that support references for this file
        return serverResolver.getLspServersForFile(
                document,
                cwd,
                server -> server.isEnabled() && server.supportsCapability(LspCapability.REFERENCES, document)
        ).thenCompose(servers -> {
            if (servers.isEmpty()) {
                return CompletableFuture.completedFuture("No language server with references support found for: " + fileUri);
            }

            // Build references parameters
            ReferenceParams params = new ReferenceParams();
            params.setTextDocument(new TextDocumentIdentifier(fileUri));
            params.setPosition(new Position(line, character));

            ReferenceContext context = new ReferenceContext();
            context.setIncludeDeclaration(true); // Include the declaration itself
            params.setContext(context);

            // Call textDocument/references on all servers in parallel
            List<CompletableFuture<List<? extends Location>>> futures = servers.stream()
                    .map(server -> server.getLanguageServer()
                            .getTextDocumentService()
                            .references(params)
                            .exceptionally(ex -> {
                                LOG.warnf("Failed to get references from server %s: %s", server.getConfig().getId(), ex.getMessage());
                                return null;
                            }))
                    .toList();

            // Register futures for automatic cancellation
            if (cancellation != null) {
                cancellationSupport.registerAll(cancellation, futures);
            }

            // Wait for all to complete and merge results
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<? extends Location> allReferences = futures.stream()
                                .map(CompletableFuture::join)
                                .filter(refs -> refs != null && !refs.isEmpty())
                                .flatMap(List::stream)
                                .toList();

                        if (allReferences.isEmpty()) {
                            return String.format("No references found for symbol at %s:%d:%d", fileUri, line + 1, character);
                        }

                        // Deduplicate based on URI + range
                        List<? extends Location> references = allReferences.stream()
                                .distinct()
                                .toList();

                        // Format results
                        StringBuilder result = new StringBuilder();
                        result.append(String.format("Found %d reference(s) for symbol at %s:%d:%d\n\n",
                                references.size(), fileUri, line + 1, character));

                        // Group by file
                        Map<String, List<Location>> byFile = references.stream()
                                .collect(Collectors.groupingBy(Location::getUri));

                        for (Map.Entry<String, List<Location>> entry : byFile.entrySet()) {
                            String file = entry.getKey();
                            List<Location> locations = entry.getValue();

                            result.append(String.format("File: %s (%d reference(s))\n", file, locations.size()));

                            for (Location location : locations) {
                                Range range = location.getRange();
                                result.append(String.format("  Line %d:%d-%d\n",
                                        range.getStart().getLine() + 1,
                                        range.getStart().getCharacter(),
                                        range.getEnd().getCharacter()));
                            }
                            result.append("\n");
                        }

                        return result.toString();
                    });
        }).exceptionally(ex -> {
            LOG.error("Failed to find references", ex);
            return "Failed to find references: " + ex.getMessage();
        });
    }
}
