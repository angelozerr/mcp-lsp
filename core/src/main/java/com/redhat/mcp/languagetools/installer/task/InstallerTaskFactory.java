package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Factory for creating installer tasks from JSON.
 * Implementations are registered via Java SPI.
 */
public interface InstallerTaskFactory {
    /**
     * Gets the task type this factory handles (e.g., "download", "fileExists").
     */
    String getType();

    /**
     * Creates a task from JSON configuration.
     *
     * @param config JSON configuration for the task
     * @return The created task
     */
    InstallerTask createTask(JsonNode config);
}
