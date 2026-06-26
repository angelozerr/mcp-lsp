package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;

/**
 * Factory for creating CopyTask instances from JSON.
 */
public class CopyTaskFactory implements InstallerTaskFactory {

    private final InstallerTaskRegistry registry;

    public CopyTaskFactory(InstallerTaskRegistry registry) {
        this.registry = registry;
    }

    @Override
    public InstallerTask create(JsonObject json) {
        String id = json.has("id") ? json.get("id").getAsString() : "copy";
        String name = json.has("name") ? json.get("name").getAsString() : "Copy file";

        // Support two syntaxes:
        // 1. Simple: { "source": "path", "destination": "path" }
        // 2. Download-like: { "file": "path", "output": { "dir": "...", "file": { "name": "..." } } }
        String source;
        String destination;

        if (json.has("source")) {
            // Simple syntax
            source = json.get("source").getAsString();
            destination = json.get("destination").getAsString();
        } else if (json.has("file") && json.has("output")) {
            // Download-like syntax
            source = json.get("file").getAsString();

            JsonObject output = json.getAsJsonObject("output");
            String outputDir = output.get("dir").getAsString();
            String outputFileName = output.getAsJsonObject("file").get("name").getAsString();
            destination = outputDir + "/" + outputFileName;
        } else {
            throw new IllegalArgumentException("Copy task requires either 'source'+'destination' or 'file'+'output'");
        }

        boolean overwrite = json.has("overwrite") ? json.get("overwrite").getAsBoolean() : true;

        // Parse optional onFail/onSuccess
        InstallerTask onFail = json.has("onFail") ? registry.loadTask(json.getAsJsonObject("onFail")) : null;
        InstallerTask onSuccess = json.has("onSuccess") ? registry.loadTask(json.getAsJsonObject("onSuccess")) : null;

        return new CopyTask(id, name, source, destination, overwrite, onFail, onSuccess);
    }
}
