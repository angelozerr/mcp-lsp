package com.redhat.mcp.languagetools.installer;

import java.nio.file.Path;

/**
 * Result of server installation.
 */
public class InstallResult {
    private final Path installDir;
    private final String command;
    private final InstallationStatus status;

    public InstallResult(Path installDir, String command, InstallationStatus status) {
        this.installDir = installDir;
        this.command = command;
        this.status = status;
    }

    public Path getInstallDir() {
        return installDir;
    }

    /**
     * Returns the command to execute the server.
     * This is always returned, even if the server was already installed.
     */
    public String getCommand() {
        return command;
    }

    public InstallationStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "InstallResult{" +
                "installDir=" + installDir +
                ", command='" + command + '\'' +
                ", status=" + status +
                '}';
    }
}
