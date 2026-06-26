package com.redhat.mcp.languagetools.lsp.installer.task;

import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;

/**
 * Configure server command after installation (like lsp4ij).
 * Example:
 * {
 *   "configureServer": {
 *     "name": "Configure jdtls server command",
 *     "command": "${output.dir}/bin/jdtls -configuration \"${user.home}/.cache/jdtls\" -data \"${workspace}\"",
 *     "update": true
 *   }
 * }
 */
public class ConfigureServerTask extends InstallerTask {

    private final String command;
    private final boolean update;

    public ConfigureServerTask(String id, String name, String command, boolean update,
                               InstallerTask onFail, InstallerTask onSuccess) {
        super(id, name, onFail, onSuccess);
        this.command = command;
        this.update = update;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedCommand = context.resolveVariables(command);

        // Store resolved command in context for server configuration update
        context.setProperty("server.command", resolvedCommand);

        // Show original template and resolved command
        String logMessage = command.equals(resolvedCommand)
            ? command  // No variables to resolve
            : command + " → " + resolvedCommand;  // Show both template and resolved

        context.log("  ✓ Configured server command: " + logMessage);
        return true;
    }

    public String getCommand() {
        return command;
    }

    public boolean isUpdate() {
        return update;
    }
}
