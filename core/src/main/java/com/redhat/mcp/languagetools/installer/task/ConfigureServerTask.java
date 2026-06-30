package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.mcp.languagetools.installer.InstallerContext;
import com.redhat.mcp.languagetools.trace.TraceCollector;

/**
 * Task that configures the server command.
 * This extracts the final command from installer.json.
 */
public class ConfigureServerTask implements InstallerTask {
    private final String name;
    private final String command;

    public ConfigureServerTask(String name, String command) {
        this.name = name;
        this.command = command;
    }

    @Override
    public boolean execute(InstallerContext context) {
        context.checkCanceled();

        String resolvedCommand = context.resolveVariables(command);

        // Store the resolved command in context
        context.setVariable("SERVER_COMMAND", resolvedCommand);

        TraceCollector trace = context.getConfig().getTraceCollector();
        if (trace != null) {
            trace.info("Server command configured: " + resolvedCommand);
        }

        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getCommand() {
        return command;
    }

    /**
     * Factory for ConfigureServerTask.
     */
    public static class Factory implements InstallerTaskFactory {
        @Override
        public String getType() {
            return "configureServer";
        }

        @Override
        public InstallerTask createTask(JsonNode config) {
            String name = config.has("name") ? config.get("name").asText() : "Configure server";
            String command = config.get("command").asText();
            return new ConfigureServerTask(name, command);
        }
    }
}
