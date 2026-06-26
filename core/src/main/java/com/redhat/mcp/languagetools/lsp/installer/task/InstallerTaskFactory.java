package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;

/**
 * Factory for creating installer tasks from JSON.
 * Based on lsp4ij InstallerTaskFactory.
 */
public interface InstallerTaskFactory {

    /**
     * Create an installer task from JSON definition.
     */
    InstallerTask create(JsonObject json);
}
