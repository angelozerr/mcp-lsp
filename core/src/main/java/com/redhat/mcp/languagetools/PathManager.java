package com.redhat.mcp.languagetools;

import com.redhat.mcp.languagetools.config.PathConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;

/**
 * Centralized path management for MCP Language Tools.
 * Handles all path resolution and variable substitution.
 * Uses PathConfig for configurable paths.
 */
@ApplicationScoped
public class PathManager {

    // File names (hardcoded constants)
    public static final String CONFIG_JSON = "config.json";
    public static final String INSTALLER_JSON = "installer.json";

    @Inject
    PathConfig pathConfig;

    /**
     * Get the root directory for MCP Language Tools (~/.mcp-languagetools by default)
     */
    public Path getMcpLangToolsRoot() {
        return pathConfig.getMcpLangToolsDir();
    }

    /**
     * Get the directory where LSP servers are installed (~/.mcp-languagetools/lsp)
     */
    public Path getLspServersDir() {
        return getMcpLangToolsRoot().resolve(pathConfig.getLspDirName());
    }

    /**
     * Get the home directory for a specific LSP server (~/.mcp-languagetools/lsp/{serverId})
     */
    public Path getServerHome(String serverId) {
        return getLspServersDir().resolve(serverId);
    }

    /**
     * Get the config directory root (~/.mcp-languagetools/config)
     */
    public Path getConfigDir() {
        return getMcpLangToolsRoot().resolve(pathConfig.getConfigDirName());
    }

    /**
     * Get the config directory for LSP servers (~/.mcp-languagetools/config/lsp)
     */
    public Path getLspConfigDir() {
        return getConfigDir().resolve(pathConfig.getLspDirName());
    }

    /**
     * Get the config directory for a specific LSP server (~/.mcp-languagetools/config/lsp/{serverId})
     */
    public Path getServerConfigDir(String serverId) {
        return getLspConfigDir().resolve(serverId);
    }

    /**
     * Get the installer.json path for a specific server (~/.mcp-languagetools/config/lsp/{serverId}/installer.json)
     */
    public Path getServerInstallerConfig(String serverId) {
        return getServerConfigDir(serverId).resolve(INSTALLER_JSON);
    }

    /**
     * Get the global config file path (~/.mcp-languagetools/config.json)
     */
    public Path getGlobalConfigFile() {
        return getMcpLangToolsRoot().resolve(CONFIG_JSON);
    }

    /**
     * Get the workspace data directory (~/.mcp-languagetools/workspaces)
     */
    public Path getWorkspaceDataDir() {
        return getMcpLangToolsRoot().resolve("workspaces");
    }

    /**
     * Resolve variables in a template string.
     * Supports: $USER_HOME$, $SERVER_HOME$
     */
    public String resolveVariables(String template, String serverId) {
        if (template == null) {
            return null;
        }
        return template
            .replace("$USER_HOME$", pathConfig.getRootDir().toString())
            .replace("$SERVER_HOME$", getServerHome(serverId).toString());
    }
}
