package com.redhat.mcp.languagetools.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Configuration for MCP Language Tools paths.
 * All paths are configurable via application.properties.
 */
@ApplicationScoped
public class PathConfig {

    // Directory structure (fixed)
    private static final String DIR_MCP_LANG_TOOLS = ".mcp-languagetools";
    private static final String DIR_LSP = "lsp";
    private static final String DIR_CONFIG = "config";
    private static final String DIR_WORKSPACES = "workspaces";

    @ConfigProperty(name = "mcp.languagetools.root")
    Optional<String> rootDir;

    /**
     * Get the root directory (user home by default).
     */
    public Path getRootDir() {
        String root = rootDir.orElse(System.getProperty("user.home"));
        return Paths.get(root);
    }

    /**
     * Get the main MCP Language Tools directory.
     * Defaults to ~/.mcp-languagetools
     */
    public Path getMcpLangToolsDir() {
        return getRootDir().resolve(DIR_MCP_LANG_TOOLS);
    }

    /**
     * Get the LSP servers directory name (lsp).
     */
    public String getLspDirName() {
        return DIR_LSP;
    }

    /**
     * Get the config directory name (config).
     */
    public String getConfigDirName() {
        return DIR_CONFIG;
    }

    /**
     * Get the workspaces directory name (workspaces).
     */
    public String getWorkspacesDirName() {
        return DIR_WORKSPACES;
    }

}
