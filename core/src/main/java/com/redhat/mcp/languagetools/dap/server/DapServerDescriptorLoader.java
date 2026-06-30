package com.redhat.mcp.languagetools.dap.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.DocumentSelector;
import com.redhat.mcp.languagetools.server.ServerDescriptorLoaderBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads DAP server descriptors from JSON files.
 */
@ApplicationScoped
public class DapServerDescriptorLoader extends ServerDescriptorLoaderBase<DapServerConfig> {

    private static final Logger LOG = Logger.getLogger(DapServerDescriptorLoader.class);

    @Inject
    PathManager pathManager;

    public DapServerDescriptorLoader() {
        super();
    }

    @Override
    protected String getResourceDirectory() {
        return "dap";
    }

    @Override
    protected DapServerConfig parseServerConfig(InputStream is, String serverId) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            DapServerConfig config = new DapServerConfig();

            // Basic fields
            config.setId(serverId);
            config.setName(root.has("name") ? root.get("name").getAsString() : serverId);
            if (root.has("description")) {
                config.setDescription(root.get("description").getAsString());
            }

            // Launch commands (OS-specific)
            if (root.has("launch")) {
                Map<String, String> launch = new HashMap<>();
                root.getAsJsonObject("launch").entrySet().forEach(entry ->
                    launch.put(entry.getKey(), entry.getValue().getAsString())
                );
                config.setLaunch(launch);
            }

            // Attach configuration
            if (root.has("attach")) {
                Map<String, Object> attach = gson.fromJson(
                    root.get("attach"),
                    Map.class
                );
                config.setAttach(attach);
            }

            // Debug server ready pattern
            if (root.has("debugServerReadyPattern")) {
                config.setDebugServerReadyPattern(root.get("debugServerReadyPattern").getAsString());
            }

            // Document selector
            if (root.has("documentSelector")) {
                List<DocumentSelector> selectors = new ArrayList<>();
                root.getAsJsonArray("documentSelector").forEach(el -> {
                    JsonObject selectorObj = el.getAsJsonObject();
                    DocumentSelector selector = new DocumentSelector();
                    if (selectorObj.has("language")) {
                        selector.setLanguage(selectorObj.get("language").getAsString());
                    }
                    if (selectorObj.has("scheme")) {
                        selector.setScheme(selectorObj.get("scheme").getAsString());
                    }
                    if (selectorObj.has("pattern")) {
                        selector.setPattern(selectorObj.get("pattern").getAsString());
                    }
                    selectors.add(selector);
                });
                config.setDocumentSelector(selectors);
            }

            // Environment variables
            if (root.has("env")) {
                Map<String, Object> env = gson.fromJson(
                    root.get("env"),
                    Map.class
                );
                config.setEnv(env);
            }

            // Working directory
            if (root.has("workingDirectory")) {
                config.setWorkingDirectory(root.get("workingDirectory").getAsString());
            }

            return config;
        }
    }

    // loadAllBundled() inherited from base class - auto-discovers servers
}
