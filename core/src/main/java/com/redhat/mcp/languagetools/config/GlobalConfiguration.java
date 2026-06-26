package com.redhat.mcp.languagetools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.redhat.mcp.languagetools.PathManager;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Global configuration stored in ~/.mcp-languagetools/config.json
 */
@ApplicationScoped
public class GlobalConfiguration {

    private static final Logger LOG = Logger.getLogger(GlobalConfiguration.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Inject
    PathManager pathManager;

    private Map<String, Object> config;

    /**
     * Initialize configuration after dependency injection.
     */
    @PostConstruct
    void init() {
        load();
    }

    private Path getConfigFile() {
        return pathManager.getGlobalConfigFile();
    }

    /**
     * Load configuration from file.
     */
    private void load() {
        if (!Files.exists(getConfigFile())) {
            LOG.infof("No global config found at %s, using defaults", getConfigFile());
            config = new HashMap<>();
            config.put("servers", new HashMap<String, Map<String, Object>>());
            return;
        }

        try {
            String json = Files.readString(getConfigFile());
            TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {};
            config = GSON.fromJson(json, typeToken.getType());
            if (config == null) {
                config = new HashMap<>();
            }
            if (!config.containsKey("servers")) {
                config.put("servers", new HashMap<String, Map<String, Object>>());
            }
            LOG.infof("Loaded global config from %s", getConfigFile());
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load global config from %s", getConfigFile());
            config = new HashMap<>();
            config.put("servers", new HashMap<String, Map<String, Object>>());
        }
    }

    /**
     * Save configuration to file.
     */
    private synchronized void save() {
        try {
            Files.createDirectories(getConfigFile().getParent());
            String json = GSON.toJson(config);
            Files.writeString(getConfigFile(), json);
            LOG.infof("Saved global config to %s", getConfigFile());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to save global config to %s", getConfigFile());
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

    /**
     * Get MCP trace level.
     * @return "off", "messages", or "verbose" (default)
     */
    public String getMcpTraceLevel() {
        Object mcpConfig = config.get("mcp");
        if (mcpConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mcp = (Map<String, Object>) mcpConfig;
            Object trace = mcp.get("trace");
            if (trace instanceof String) {
                return (String) trace;
            }
        }
        return "verbose"; // Default
    }

    /**
     * Set MCP trace level.
     */
    public synchronized void setMcpTraceLevel(String level) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mcp = (Map<String, Object>) config.computeIfAbsent("mcp", k -> new HashMap<>());
        mcp.put("trace", level);

        save();
        LOG.infof("Set MCP trace level: %s", level);
    }
}
