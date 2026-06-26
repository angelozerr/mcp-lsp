package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;

/**
 * Factory for ConfigureServerTask.
 * Example JSON:
 * {
 *   "configureServer": {
 *     "name": "Configure server command",
 *     "command": "${output.dir}/bin/jdtls -configuration \"${user.home}/.cache/jdtls\" -data \"${workspace}\"",
 *     "update": true
 *   }
 * }
 */
public class ConfigureServerTaskFactory extends InstallerTaskFactoryBase {

    private static final String COMMAND_JSON_PROPERTY = "command";
    private static final String UPDATE_JSON_PROPERTY = "update";

    public ConfigureServerTaskFactory(InstallerTaskRegistry registry) {
        super(registry);
    }

    @Override
    protected InstallerTask create(String id, String name, InstallerTask onFail,
                                    InstallerTask onSuccess, JsonObject json) {
        String command = getString(json, COMMAND_JSON_PROPERTY);
        if (command == null) {
            throw new IllegalArgumentException("Missing 'command' property in configureServer task");
        }

        boolean update = getBoolean(json, UPDATE_JSON_PROPERTY);

        return new ConfigureServerTask(id, name, command, update, onFail, onSuccess);
    }
}
