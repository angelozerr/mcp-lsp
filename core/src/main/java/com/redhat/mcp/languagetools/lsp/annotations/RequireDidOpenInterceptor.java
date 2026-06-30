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
package com.redhat.mcp.languagetools.lsp.annotations;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.language.LanguageRegistry;
import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.workspace.Workspace;
import com.redhat.mcp.languagetools.ApplicationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Interceptor that automatically handles didOpen/didClose for tools annotated with @RequireDidOpen.
 *
 * Note: Quarkus CDI interceptors work on CDI beans (@ApplicationScoped, @RequestScoped, etc.).
 * This interceptor binds to the @RequireDidOpen annotation itself (meta-annotation pattern).
 */
@RequireDidOpen
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class RequireDidOpenInterceptor {

    private static final Logger LOG = Logger.getLogger(RequireDidOpenInterceptor.class);

    @Inject
    ApplicationManager applicationManager;

    @Inject
    LanguageRegistry languageRegistry;

    public RequireDidOpenInterceptor() {
        LOG.info("RequireDidOpenInterceptor instantiated");
    }

    @AroundInvoke
    public Object ensureFileOpened(InvocationContext context) throws Exception {
        Method method = context.getMethod();
        RequireDidOpen annotation = method.getAnnotation(RequireDidOpen.class);

        LOG.infof("RequireDidOpenInterceptor called for method: %s", method.getName());

        if (annotation == null) {
            LOG.warnf("No @RequireDidOpen annotation on method %s, skipping", method.getName());
            return context.proceed();
        }

        // Find the URI parameter by annotation, not by name (names are lost at compile time)
        String uriParamName = annotation.uriParam();
        Parameter[] parameters = method.getParameters();
        Object[] args = context.getParameters();

        String fileUri = null;

        // Strategy 1: Find parameter with @ToolArg whose description contains "URI" or matches uriParamName pattern
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(io.quarkiverse.mcp.server.ToolArg.class)) {
                io.quarkiverse.mcp.server.ToolArg toolArg = parameters[i].getAnnotation(io.quarkiverse.mcp.server.ToolArg.class);
                String description = toolArg.description().toLowerCase();

                // Match if description contains "file" and "uri", or if it's explicitly the URI param
                if ((description.contains("file") && description.contains("uri")) ||
                    description.contains("file uri")) {
                    fileUri = (String) args[i];
                    LOG.debugf("Found fileUri parameter at index %d via @ToolArg description", i);
                    break;
                }
            }
        }

        // Strategy 2: Fallback - if only one String parameter, use it
        if (fileUri == null && parameters.length > 0) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getType() == String.class && args[i] != null) {
                    String value = (String) args[i];
                    // Check if it looks like a URI
                    if (value.startsWith("file:") || value.startsWith("http:") || value.startsWith("https:")) {
                        fileUri = value;
                        LOG.debugf("Found fileUri parameter at index %d via URI pattern", i);
                        break;
                    }
                }
            }
        }

        if (fileUri == null) {
            LOG.warnf("@RequireDidOpen on %s but could not find fileUri parameter (searched by @ToolArg description)", method.getName());
            return context.proceed();
        }

        LOG.infof("Processing @RequireDidOpen for file: %s", fileUri);

        URI uri = URI.create(fileUri);
        Workspace workspace = applicationManager.getWorkspaceForFile(uri).join();

        if (workspace == null) {
            LOG.warnf("No workspace found for %s", fileUri);
            return context.proceed();
        }

        // Find servers that handle this file
        List<LspServer> serversToNotify = new ArrayList<>();
        for (LspServer server : workspace.getAllLspServers().values()) {
            if (server.getConfig().canHandle(fileUri, detectLanguage(uri, annotation.languageId()))) {
                serversToNotify.add(server);
            }
        }

        if (serversToNotify.isEmpty()) {
            LOG.debugf("No LSP servers available for %s", fileUri);
            return context.proceed();
        }

        // Check if file is already opened
        List<LspServer> serversNeedingDidOpen = new ArrayList<>();
        for (LspServer server : serversToNotify) {
            if (!server.isFileOpened(fileUri)) {
                serversNeedingDidOpen.add(server);
            }
        }

        if (serversNeedingDidOpen.isEmpty()) {
            // Already opened, just proceed
            return context.proceed();
        }

        // Read file content from disk
        String content;
        try {
            content = Files.readString(Paths.get(uri));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to read file %s for didOpen", fileUri);
            return context.proceed();
        }

        String languageId = detectLanguage(uri, annotation.languageId());

        // Send didOpen to servers that need it
        for (LspServer server : serversNeedingDidOpen) {
            try {
                DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
                TextDocumentItem textDocument = new TextDocumentItem();
                textDocument.setUri(fileUri);
                textDocument.setLanguageId(languageId);
                textDocument.setVersion(1);
                textDocument.setText(content);
                params.setTextDocument(textDocument);

                server.getLanguageServer().getTextDocumentService().didOpen(params);
                server.markFileOpened(fileUri);
                LOG.debugf("Sent didOpen for %s to %s", fileUri, server.getConfig().getId());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to send didOpen for %s to %s", fileUri, server.getConfig().getId());
            }
        }

        // Execute the tool
        Object result;
        try {
            result = context.proceed();
        } finally {
            // Send didClose to servers we opened
            for (LspServer server : serversNeedingDidOpen) {
                try {
                    DidCloseTextDocumentParams params = new DidCloseTextDocumentParams();
                    TextDocumentIdentifier textDocument = new TextDocumentIdentifier(fileUri);
                    params.setTextDocument(textDocument);

                    server.getLanguageServer().getTextDocumentService().didClose(params);
                    server.markFileClosed(fileUri);
                    LOG.debugf("Sent didClose for %s to %s", fileUri, server.getConfig().getId());
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to send didClose for %s to %s", fileUri, server.getConfig().getId());
                }
            }
        }

        return result;
    }

    private String detectLanguage(URI uri, String annotationLanguageId) {
        // If annotation specifies a language, use it
        if (annotationLanguageId != null && !annotationLanguageId.isEmpty()) {
            return annotationLanguageId;
        }

        // Auto-detect from file extension
        Optional<String> detected = languageRegistry.detectLanguage(uri);
        return detected.orElse("plaintext");
    }
}
