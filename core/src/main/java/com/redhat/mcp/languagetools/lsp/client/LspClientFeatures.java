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

import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.language.LanguageDocument;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.client.capabilities.ReferencesCapabilityRegistry;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.UnregistrationParams;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP client features - manages server capabilities (static and dynamic).
 */
public class LspClientFeatures {

    private final LspServerConfig config;

    // Capability registries
    private ReferencesCapabilityRegistry referencesRegistry;

    // Dynamic capabilities registered via client/registerCapability
    private final Map<String, Runnable> dynamicRegistrations = new ConcurrentHashMap<>();

    public LspClientFeatures(LspServerConfig config) {
        this.config = config;
        // Initialize registries
        this.referencesRegistry = new ReferencesCapabilityRegistry(this);
    }

    /**
     * Set server capabilities from initialize response.
     */
    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        // Update all registries
        referencesRegistry.setServerCapabilities(serverCapabilities);
    }

    /**
     * Check if the language server is enabled.
     * This can be controlled by user configuration.
     */
    public boolean isEnabled() {
        // TODO: Check user configuration to see if server is disabled
        // For now, always enabled
        return true;
    }

    /**
     * Check if the server supports a given capability for a file.
     *
     * @param capability the LSP capability to check
     * @param document   the language document
     * @return true if the capability is supported
     */
    public boolean supportsCapability(LspCapability capability, LanguageDocument document) {
        return switch (capability) {
            case REFERENCES -> referencesRegistry.isReferencesSupported(document);
            // TODO: Add other capabilities
            case DEFINITION, HOVER, COMPLETION, DIAGNOSTIC, DOCUMENT_SYMBOL -> false;
        };
    }

    /**
     * Register a dynamic capability.
     * Called when the server sends a client/registerCapability notification.
     */
    public void registerCapability(RegistrationParams params) {
        params.getRegistrations().forEach(reg -> {
            String id = reg.getId();
            String method = reg.getMethod();
            Object registerOptions = reg.getRegisterOptions();

            if (!(registerOptions instanceof JsonObject)) {
                return;
            }

            JsonObject jsonOptions = (JsonObject) registerOptions;

            // Delegate to appropriate registry based on method
            if (LspRequestConstants.TEXT_DOCUMENT_REFERENCES.equals(method)) {
                var options = referencesRegistry.registerCapability(jsonOptions);
                dynamicRegistrations.put(id, () -> referencesRegistry.unregisterCapability(options));
            }
            // TODO: Add other capabilities
        });
    }

    /**
     * Unregister a dynamic capability.
     * Called when the server sends a client/unregisterCapability notification.
     */
    public void unregisterCapability(UnregistrationParams params) {
        params.getUnregisterations().forEach(unreg -> {
            String id = unreg.getId();
            Runnable unregisterHandler = dynamicRegistrations.remove(id);
            if (unregisterHandler != null) {
                unregisterHandler.run();
            }
        });
    }

    public LspServerConfig getConfig() {
        return config;
    }

    public ReferencesCapabilityRegistry getReferencesRegistry() {
        return referencesRegistry;
    }
}
