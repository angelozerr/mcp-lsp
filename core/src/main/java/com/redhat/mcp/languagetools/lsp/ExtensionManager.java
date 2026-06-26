package com.redhat.mcp.languagetools.lsp;

import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.*;

/**
 * Manages server extensions (VS Code-like contribution system).
 * Collects contributions from all server descriptors and makes them available to target servers.
 */
public class ExtensionManager {

    private static final Logger LOG = Logger.getLogger(ExtensionManager.class);

    private final Map<String, LspServerConfig> allConfigs;
    private final Path serversBaseDir;

    public ExtensionManager(Map<String, LspServerConfig> allConfigs, Path serversBaseDir) {
        this.allConfigs = allConfigs;
        this.serversBaseDir = serversBaseDir;
    }

    /**
     * Collect all bundles contributed to JDT.LS (contributes.jdtls.bundles).
     * Returns absolute paths to the bundle JARs.
     *
     * @deprecated Use direct access to contributes.jdtls in JdtLsServer.prepareInitializationOptions()
     */
    @Deprecated
    public List<Path> collectJavaExtensions() {
        List<Path> extensions = new ArrayList<>();

        for (LspServerConfig config : allConfigs.values()) {
            if (config.getContributes() == null) {
                continue;
            }

            // Read contributes.jdtls.bundles
            com.google.gson.JsonElement jdtlsContrib = config.getContributes().getContribution("jdtls");
            if (jdtlsContrib != null && jdtlsContrib.isJsonObject()) {
                com.google.gson.JsonObject jdtlsObj = jdtlsContrib.getAsJsonObject();
                if (jdtlsObj.has("bundles") && jdtlsObj.get("bundles").isJsonArray()) {
                    for (com.google.gson.JsonElement bundleElem : jdtlsObj.getAsJsonArray("bundles")) {
                        String bundlePath = bundleElem.getAsString();
                        Path absolutePath = resolveExtensionPath(config.getId(), bundlePath);
                        if (absolutePath != null) {
                            extensions.add(absolutePath);
                            LOG.infof("Collected bundle from %s: %s", config.getId(), absolutePath);
                        }
                    }
                }
            }
        }

        return extensions;
    }

    /**
     * Get contributions for a specific target server.
     *
     * @param targetServerId The server that will receive the contributions (e.g., "microprofile")
     * @return Map of contributor server ID -> contribution data
     */
    public Map<String, com.google.gson.JsonElement> getContributionsFor(String targetServerId) {
        Map<String, com.google.gson.JsonElement> contributions = new HashMap<>();

        for (LspServerConfig config : allConfigs.values()) {
            if (config.getContributes() == null) {
                continue;
            }

            if (config.getContributes().hasContribution(targetServerId)) {
                com.google.gson.JsonElement contribution = config.getContributes().getContribution(targetServerId);
                contributions.put(config.getId(), contribution);
                LOG.infof("Server %s contributes to %s", config.getId(), targetServerId);
            }
        }

        return contributions;
    }

    /**
     * Resolve extension path relative to the contributor server's directory.
     * Handles paths like "./jars/plugin.jar" or "./server/extension.jar"
     */
    private Path resolveExtensionPath(String contributorServerId, String extensionPath) {
        // Remove leading "./" if present
        String normalizedPath = extensionPath.startsWith("./") ? extensionPath.substring(2) : extensionPath;

        // Resolve relative to the contributor server's directory
        Path serverDir = serversBaseDir.resolve(contributorServerId);
        Path resolved = serverDir.resolve(normalizedPath);

        if (!java.nio.file.Files.exists(resolved)) {
            LOG.warnf("Extension not found: %s (from server %s)", resolved, contributorServerId);
            return null;
        }

        return resolved;
    }

    /**
     * Resolve multiple extension paths.
     */
    public List<Path> resolveExtensionPaths(String contributorServerId, List<String> extensionPaths) {
        List<Path> resolved = new ArrayList<>();
        if (extensionPaths != null) {
            for (String path : extensionPaths) {
                Path absolutePath = resolveExtensionPath(contributorServerId, path);
                if (absolutePath != null) {
                    resolved.add(absolutePath);
                }
            }
        }
        return resolved;
    }
}
