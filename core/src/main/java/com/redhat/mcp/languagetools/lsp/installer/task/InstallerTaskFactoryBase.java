package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Base class for installer task factories.
 * Handles common JSON parsing (id, name, onFail, onSuccess).
 * Based on lsp4ij InstallerTaskFactoryBase.
 */
public abstract class InstallerTaskFactoryBase implements InstallerTaskFactory {

    private static final String ID_JSON_PROPERTY = "id";
    private static final String NAME_JSON_PROPERTY = "name";
    private static final String ON_FAIL_JSON_PROPERTY = "onFail";
    private static final String ON_SUCCESS_JSON_PROPERTY = "onSuccess";

    private final InstallerTaskRegistry registry;

    protected InstallerTaskFactoryBase(InstallerTaskRegistry registry) {
        this.registry = registry;
    }

    @Override
    public final InstallerTask create(JsonObject json) {
        String id = getString(json, ID_JSON_PROPERTY);
        String name = getString(json, NAME_JSON_PROPERTY);
        InstallerTask onFail = loadOnFail(json);
        InstallerTask onSuccess = loadOnSuccess(json);
        return create(id, name, onFail, onSuccess, json);
    }

    private InstallerTask loadOnFail(JsonObject json) {
        JsonObject onFailObj = getJsonObject(json, ON_FAIL_JSON_PROPERTY);
        if (onFailObj == null) {
            return null;
        }
        return registry.loadTask(onFailObj);
    }

    private InstallerTask loadOnSuccess(JsonObject json) {
        JsonObject onSuccessObj = getJsonObject(json, ON_SUCCESS_JSON_PROPERTY);
        if (onSuccessObj == null) {
            return null;
        }
        return registry.loadTask(onSuccessObj);
    }

    protected abstract InstallerTask create(String id, String name, InstallerTask onFail,
                                            InstallerTask onSuccess, JsonObject json);

    /**
     * Get string from JSON, supporting OS-specific values.
     * Examples:
     * - "url": "https://..."
     * - "url": {"windows": "...", "default": "..."}
     * - "url": {"windows": {"x86_64": "...", "arm64": "..."}, "default": "..."}
     */
    protected static String getStringFromOs(JsonObject json, String name) {
        if (!json.has(name)) {
            return null;
        }

        JsonElement element = json.get(name);

        // Simple string
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }

        // OS-specific object
        if (element.isJsonObject()) {
            JsonObject osObj = element.getAsJsonObject();
            String os = getOsKey();

            // Try OS-specific key (e.g., "windows", "linux", "mac")
            if (osObj.has(os)) {
                element = osObj.get(os);
            } else if (osObj.has("default")) {
                element = osObj.get("default");
            } else {
                return null;
            }

            // Architecture-specific (e.g., {"x86_64": "...", "arm64": "..."})
            if (element.isJsonObject()) {
                JsonObject archObj = element.getAsJsonObject();
                String arch = System.getProperty("os.arch");

                if (archObj.has(arch)) {
                    element = archObj.get(arch);
                } else if (archObj.has("default")) {
                    element = archObj.get("default");
                } else {
                    return null;
                }
            }

            // Return final string value
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return element.getAsString();
            }
        }

        return null;
    }

    private static String getOsKey() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("mac")) {
            return "mac";
        } else if (osName.contains("linux")) {
            return "linux";
        }
        return "unix";
    }

    protected static String getString(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return json.get(key).getAsString();
        }
        return null;
    }

    protected static boolean getBoolean(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return json.get(key).getAsBoolean();
        }
        return false;
    }

    protected static JsonObject getJsonObject(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonObject()) {
            return json.get(key).getAsJsonObject();
        }
        return null;
    }
}
