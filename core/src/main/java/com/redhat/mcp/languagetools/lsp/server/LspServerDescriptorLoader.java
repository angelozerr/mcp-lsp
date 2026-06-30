package com.redhat.mcp.languagetools.lsp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.Contributes;
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
 * Loads LSP server descriptors from JSON files.
 */
@ApplicationScoped
public class LspServerDescriptorLoader extends ServerDescriptorLoaderBase<LspServerConfig> {

    private static final Logger LOG = Logger.getLogger(LspServerDescriptorLoader.class);

    @Inject
    PathManager pathManager;

    public LspServerDescriptorLoader() {
        super();
    }

    @Override
    protected String getResourceDirectory() {
        return "lsp";
    }

    @Override
    protected void markAsExtension(LspServerConfig config, boolean isExtension) {
        config.setExtension(isExtension);
    }

    @Override
    protected LspServerConfig parseServerConfig(InputStream is, String serverId) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            LspServerConfig config = new LspServerConfig();

            // Basic fields
            config.setId(serverId);
            config.setName(root.has("name") ? root.get("name").getAsString() : serverId);
            if (root.has("description")) {
                config.setDescription(root.get("description").getAsString());
            }

            // Command
            if (root.has("command")) {
                JsonElement cmdElement = root.get("command");
                if (cmdElement.isJsonPrimitive()) {
                    config.setCommand(cmdElement.getAsString());
                } else if (cmdElement.isJsonObject()) {
                    // OS-specific commands
                    Map<String, String> osCommands = new HashMap<>();
                    cmdElement.getAsJsonObject().entrySet().forEach(entry ->
                        osCommands.put(entry.getKey(), entry.getValue().getAsString())
                    );
                    config.setCommand(osCommands);
                }
            }

            // Args
            if (root.has("args")) {
                List<String> args = new ArrayList<>();
                root.getAsJsonArray("args").forEach(el -> args.add(el.getAsString()));
                config.setArgs(args);
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

            // Initialization options
            if (root.has("initializationOptions")) {
                Map<String, Object> initOptions = gson.fromJson(
                    root.get("initializationOptions"),
                    Map.class
                );
                config.setInitializationOptions(initOptions);
            }

            // Contributes
            if (root.has("contributes")) {
                JsonElement contributesEl = root.get("contributes");
                if (contributesEl.isJsonObject()) {
                    Contributes contributes = new Contributes();
                    Map<String, JsonElement> contributionsMap = new HashMap<>();
                    contributesEl.getAsJsonObject().entrySet().forEach(entry -> {
                        contributionsMap.put(entry.getKey(), entry.getValue());
                    });
                    contributes.setContributions(contributionsMap);
                    config.setContributes(contributes);
                }
            }

            // Installer (deprecated in server.json, should be in separate installer.json)
            if (root.has("installer")) {
                LOG.warnf("Installer config in server.json is deprecated, use separate installer.json for: %s", config.getId());
                try {
                    JsonNode installerConfig = objectMapper.readTree(root.get("installer").toString());
                    config.setInstallerConfig(installerConfig);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to parse installer config for: %s", config.getId());
                }
            }

            // Environment variables
            if (root.has("env")) {
                Map<String, String> env = new HashMap<>();
                root.getAsJsonObject("env").entrySet().forEach(entry ->
                    env.put(entry.getKey(), entry.getValue().getAsString())
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
