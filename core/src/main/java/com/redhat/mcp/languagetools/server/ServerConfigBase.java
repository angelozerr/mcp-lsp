package com.redhat.mcp.languagetools.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.mcp.languagetools.installer.ServerConfig;
import com.redhat.mcp.languagetools.installer.ServerInstaller;
import com.redhat.mcp.languagetools.installer.TaskRegistryInstaller;
import com.redhat.mcp.languagetools.lsp.DocumentSelector;
import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for server configurations (LSP and DAP).
 * Contains common fields: id, name, description, installer, documentSelector.
 */
public abstract class ServerConfigBase implements ServerConfig {

    protected String id;
    protected String name;
    protected String description;
    protected JsonNode installerConfig;  // Raw JSON from installer.json
    protected List<DocumentSelector> documentSelector = new ArrayList<>();

    // Trace collector (set by workspace/session when server is added)
    protected TraceCollector traceCollector;

    // Lazy-loaded installer instance
    private ServerInstaller installer;

    // Common getters

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public JsonNode getInstallerConfig() {
        return installerConfig;
    }

    public void setInstallerConfig(JsonNode installerConfig) {
        this.installerConfig = installerConfig;
    }

    /**
     * Gets the installer instance (lazy-loaded).
     * Returns null if no installer configuration is present.
     */
    public ServerInstaller getInstaller() {
        if (installer == null && installerConfig != null) {
            installer = createInstaller();
        }
        return installer;
    }

    /**
     * Creates the installer instance from configuration.
     * Override this method to use a different installer implementation.
     */
    protected ServerInstaller createInstaller() {
        if (installerConfig == null) {
            return null;
        }
        return new TaskRegistryInstaller(this);
    }

    /**
     * Gets the trace collector for this server.
     */
    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    /**
     * Sets the trace collector for this server.
     */
    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    public List<DocumentSelector> getDocumentSelector() {
        return documentSelector;
    }

    public void setDocumentSelector(List<DocumentSelector> documentSelector) {
        this.documentSelector = documentSelector;
    }
}
