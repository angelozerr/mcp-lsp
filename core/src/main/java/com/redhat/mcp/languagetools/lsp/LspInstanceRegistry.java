package com.redhat.mcp.languagetools.lsp;

import com.google.gson.Gson;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility to read language server instance files from ${workspace}/.lsp-servers/{serverType}.json
 */
public class LspInstanceRegistry {

    private static final Logger LOG = Logger.getLogger(LspInstanceRegistry.class);
    private static final Gson GSON = new Gson();
    private static final String INSTANCES_DIR = ".lsp-servers";

    /**
     * Information about a running language server instance.
     */
    public static class InstanceInfo {
        public int port;
        public long pid;
        public String clientName;      // Optional: name of the IDE (e.g., "VS Code", "IntelliJ IDEA")
        public String clientVersion;   // Optional: version of the IDE

        @Override
        public String toString() {
            if (clientName != null) {
                return String.format("InstanceInfo{port=%d, pid=%d, client=%s %s}",
                    port, pid, clientName, clientVersion != null ? clientVersion : "");
            }
            return String.format("InstanceInfo{port=%d, pid=%d}", port, pid);
        }
    }

    /**
     * Find a running language server instance for the given workspace and type.
     * Reads from ${workspace}/.lsp-servers/{serverType}.json
     *
     * @param workspacePath the workspace path
     * @param serverType    the server type (e.g., "lemminx", "jdtls")
     * @return InstanceInfo if found and process is alive, null otherwise
     */
    public static InstanceInfo findInstance(String workspacePath, String serverType) {
        Path instanceFile = getInstanceFilePath(workspacePath, serverType);

        if (!Files.exists(instanceFile)) {
            LOG.debugf("Instance file does not exist: %s", instanceFile);
            return null;
        }

        try (Reader reader = Files.newBufferedReader(instanceFile)) {
            InstanceInfo info = GSON.fromJson(reader, InstanceInfo.class);

            if (info == null) {
                LOG.debugf("Failed to parse instance file: %s", instanceFile);
                return null;
            }

            if (!isProcessAlive(info.pid)) {
                LOG.debugf("Process %d is not alive", info.pid);
                return null;
            }

            LOG.infof("Found running %s instance: %s", serverType, info);
            return info;

        } catch (IOException e) {
            LOG.debugf("Failed to read instance file %s: %s", instanceFile, e.getMessage());
            return null;
        }
    }

    /**
     * Get the instance file path for a workspace and server type.
     * Returns ${workspace}/.lsp-servers/{serverType}.json
     */
    public static Path getInstanceFilePath(String workspacePath, String serverType) {
        return Paths.get(workspacePath, INSTANCES_DIR, serverType + ".json");
    }

    /**
     * Get the instances directory path for a workspace.
     * Returns ${workspace}/.lsp-servers
     */
    public static Path getInstancesDir(String workspacePath) {
        return Paths.get(workspacePath, INSTANCES_DIR);
    }

    /**
     * Get the instance file name for a server type.
     * Returns {serverType}.json (e.g., "lemminx.json")
     */
    public static String getInstanceFileName(String serverType) {
        return serverType + ".json";
    }

    /**
     * Check if a process with the given PID is still alive.
     */
    private static boolean isProcessAlive(long pid) {
        try {
            return ProcessHandle.of(pid)
                    .map(ProcessHandle::isAlive)
                    .orElse(false);
        } catch (Exception e) {
            LOG.debugf("Failed to check if process %d is alive: %s", pid, e.getMessage());
            return false;
        }
    }
}
