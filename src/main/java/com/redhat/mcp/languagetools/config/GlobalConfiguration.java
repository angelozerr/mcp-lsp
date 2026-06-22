package com.redhat.mcp.languagetools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Global configuration stored in ~/.mcp-lsp/config.json
 */
@ApplicationScoped
public class GlobalConfiguration {

    private static final Logger LOG = Logger.getLogger(GlobalConfiguration.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = Paths.get(System.getProperty("user.home"), ".mcp-lsp", "config.json");

    private Map<String, Object> config;

    public GlobalConfiguration() {
        load();
    }

    /**
     * Load configuration from file.
     */
    private void load() {
        if (!Files.exists(CONFIG_FILE)) {
            LOG.infof("No global config found at %s, using defaults", CONFIG_FILE);
            config = new HashMap<>();
            config.put("servers", new HashMap<String, Map<String, Object>>());
            return;
        }

        try {
            String json = Files.readString(CONFIG_FILE);
            TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {};
            config = GSON.fromJson(json, typeToken.getType());
            if (config == null) {
                config = new HashMap<>();
            }
            if (!config.containsKey("servers")) {
                config.put("servers", new HashMap<String, Map<String, Object>>());
            }
            LOG.infof("Loaded global config from %s", CONFIG_FILE);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load global config from %s", CONFIG_FILE);
            config = new HashMap<>();
            config.put("servers", new HashMap<String, Map<String, Object>>());
        }
    }

    /**
     * Save configuration to file.
     */
    private synchronized void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_FILE, json);
            LOG.infof("Saved global config to %s", CONFIG_FILE);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to save global config to %s", CONFIG_FILE);
        }
    }

    /**
     * Get trace level for a specific server.
     * @return "off", "messages", or "verbose" (default)
     */
    public String getServerTraceLevel(String serverId) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> servers = (Map<String, Map<String, Object>>) config.get("servers");

        if (servers == null) {
            return "verbose";
        }

        Map<String, Object> serverConfig = servers.get(serverId);
        if (serverConfig == null || !serverConfig.containsKey("trace")) {
            return "verbose";
        }

        return (String) serverConfig.get("trace");
    }

    /**
     * Set trace level for a specific server.
     */
    public synchronized void setServerTraceLevel(String serverId, String level) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> servers = (Map<String, Map<String, Object>>) config.get("servers");

        if (servers == null) {
            servers = new HashMap<>();
            config.put("servers", servers);
        }

        Map<String, Object> serverConfig = servers.computeIfAbsent(serverId, k -> new HashMap<>());
        serverConfig.put("trace", level);

        save();
        LOG.infof("Set trace level for %s: %s", serverId, level);
    }
}
