package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry of installer task factories.
 * Maps task names (e.g., "download", "fileExists", "configureServer") to their factories.
 */
public class InstallerTaskRegistry {

    private final Map<String, InstallerTaskFactory> factories = new HashMap<>();

    public InstallerTaskRegistry() {
        // Register default factories
        registerFactory("fileExists", new FileExistsTaskFactory(this));
        registerFactory("download", new DownloadTaskFactory(this));
        registerFactory("configureServer", new ConfigureServerTaskFactory(this));
        registerFactory("copy", new CopyTaskFactory(this));
    }

    public void registerFactory(String taskName, InstallerTaskFactory factory) {
        factories.put(taskName, factory);
    }

    /**
     * Load a task from JSON.
     * The JSON should have one root key matching a registered task name.
     * Example: {"download": {...}} or {"fileExists": {...}}
     */
    public InstallerTask loadTask(JsonObject json) {
        // Find the task type (first key in the object)
        for (String taskType : json.keySet()) {
            InstallerTaskFactory factory = factories.get(taskType);
            if (factory != null) {
                JsonObject taskJson = json.getAsJsonObject(taskType);
                return factory.create(taskJson);
            }
        }

        throw new IllegalArgumentException("Unknown installer task type in JSON: " + json.keySet());
    }
}
