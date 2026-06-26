package com.redhat.mcp.languagetools.workspace;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Workspace configuration reader.
 * Reads settings from .vscode/settings.json (VS Code compatible).
 */
public class WorkspaceConfiguration {

    private static final Logger LOG = Logger.getLogger(WorkspaceConfiguration.class);
    private static final Gson GSON = new Gson();

    private final Path workspaceRoot;
    private final Map<String, Object> settings;

    public WorkspaceConfiguration(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.settings = loadSettings();
    }

    /**
     * Load settings from .vscode/settings.json.
     */
    private Map<String, Object> loadSettings() {
        Path settingsFile = workspaceRoot.resolve(".vscode").resolve("settings.json");

        if (!Files.exists(settingsFile)) {
            LOG.debugf("No settings file found at: %s", settingsFile);
            return new HashMap<>();
        }

        try {
            String json = Files.readString(settingsFile);
            TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {};
            Map<String, Object> loaded = GSON.fromJson(json, typeToken.getType());
            LOG.infof("Loaded workspace settings from: %s", settingsFile);
            return loaded != null ? loaded : new HashMap<>();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load settings from %s", settingsFile);
            return new HashMap<>();
        }
    }

    /**
     * Get a configuration value.
     * Supports nested keys with dot notation (e.g., "java.jdt.ls.vmargs").
     *
     * @param key configuration key
     * @return value or null if not found
     */
    public Object get(String key) {
        return get(key, null);
    }

    /**
     * Get a configuration value with a default.
     *
     * @param key configuration key
     * @param defaultValue default value if key not found
     * @return value or defaultValue if not found
     */
    public Object get(String key, Object defaultValue) {
        Object value = getNestedValue(settings, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get a string configuration value.
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * Get a string configuration value with a default.
     */
    public String getString(String key, String defaultValue) {
        Object value = get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * Get a boolean configuration value.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Get an integer configuration value.
     */
    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Get nested value using dot notation (e.g., "java.jdt.ls.vmargs").
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // Direct key lookup first (handles keys with dots in them)
        if (map.containsKey(key)) {
            return map.get(key);
        }

        // Try nested lookup
        String[] parts = key.split("\\.", 2);
        if (parts.length == 1) {
            return map.get(key);
        }

        Object current = map.get(parts[0]);
        if (current instanceof Map) {
            return getNestedValue((Map<String, Object>) current, parts[1]);
        }

        return null;
    }

    /**
     * Reload settings from disk.
     */
    public void reload() {
        settings.clear();
        settings.putAll(loadSettings());
    }

    /**
     * Get all settings.
     */
    public Map<String, Object> getAll() {
        return new HashMap<>(settings);
    }
}
