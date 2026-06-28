package com.redhat.mcp.languagetools.lsp;

import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.*;

/**
 * Manages LSP server contributions (VS Code-like contribution system).
 * Collects contributions from all server descriptors and makes them available to target servers.
 * Application-scoped to preserve parent-child relationships across all workspaces.
 */
@ApplicationScoped
public class LspContributionManager {

    private static final Logger LOG = Logger.getLogger(LspContributionManager.class);

    @Inject
    PathManager pathManager;

    private Map<String, LspServerConfig> allConfigs;
    private Path serversBaseDir;
    private final Map<String, String> parentServers = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Initialize the contribution manager with server configs.
     * Called by WorkspaceManager after loading configs.
     * Analyzes contributions to build parent-child relationships.
     */
    public void initialize(Map<String, LspServerConfig> allConfigs) {
        this.allConfigs = allConfigs;
        this.serversBaseDir = pathManager.getLspServersDir();

        // Build parent-child relationships from contributions
        analyzeContributions();
    }

    /**
     * Analyze all contributions to determine parent-child relationships.
     * A server that contributes via "classpath" to another server is its extension/child.
     */
    private void analyzeContributions() {
        parentServers.clear();
        LOG.infof("Analyzing contributions from %d servers", allConfigs.size());

        for (LspServerConfig config : allConfigs.values()) {
            LOG.infof("Checking server: %s, hasContributes: %s", config.getId(), config.getContributes() != null);

            if (config.getContributes() == null) {
                continue;
            }

            // Check all contribution targets
            for (String targetServerId : allConfigs.keySet()) {
                if (config.getContributes().hasContribution(targetServerId)) {
                    LOG.infof("Server %s contributes to %s", config.getId(), targetServerId);
                    com.google.gson.JsonElement contribution = config.getContributes().getContribution(targetServerId);

                    if (contribution != null && contribution.isJsonObject()) {
                        com.google.gson.JsonObject contribObj = contribution.getAsJsonObject();
                        LOG.infof("Contribution has classpath: %s", contribObj.has("classpath"));

                        // If contributing via classpath, register parent relationship
                        if (contribObj.has("classpath")) {
                            registerParentServer(config.getId(), targetServerId);
                            LOG.infof("Registered parent relationship at startup: %s extends %s", config.getId(), targetServerId);
                        }
                    }
                }
            }
        }

        LOG.infof("Parent relationships registered: %s", parentServers);
    }

    /**
     * Register a parent-child relationship between servers.
     * Called when a server extends another server (e.g., via classpath contributions).
     */
    public void registerParentServer(String extensionId, String parentServerId) {
        parentServers.put(extensionId, parentServerId);
        LOG.infof("Registered parent relationship: %s extends %s", extensionId, parentServerId);
    }

    /**
     * Get the parent server ID for an extension.
     * Returns null if not an extension or has no parent.
     */
    public String getParentServerId(String extensionId) {
        return parentServers.get(extensionId);
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
     * Handles paths like "./jars/plugin.jar" or glob patterns like "lib/*.jar"
     */
    private Path resolveExtensionPath(String contributorServerId, String extensionPath) {
        // Remove leading "./" if present
        String normalizedPath = extensionPath.startsWith("./") ? extensionPath.substring(2) : extensionPath;

        // Resolve relative to the contributor server's directory
        Path serverDir = serversBaseDir.resolve(contributorServerId);

        // Check if pattern contains glob characters
        if (normalizedPath.contains("*") || normalizedPath.contains("?")) {
            LOG.warnf("Glob pattern detected: %s - use resolveExtensionPaths() instead", extensionPath);
            return null;
        }

        Path resolved = serverDir.resolve(normalizedPath);

        if (!java.nio.file.Files.exists(resolved)) {
            LOG.warnf("Extension not found: %s (from server %s)", resolved, contributorServerId);
            return null;
        }

        return resolved;
    }

    /**
     * Resolve multiple extension paths (supports glob patterns like "lib/*.jar").
     */
    public List<Path> resolveExtensionPaths(String contributorServerId, List<String> extensionPaths) {
        List<Path> resolved = new ArrayList<>();
        if (extensionPaths != null) {
            for (String pathPattern : extensionPaths) {
                resolved.addAll(resolvePathPattern(contributorServerId, pathPattern));
            }
        }
        return resolved;
    }

    /**
     * Resolve a single path pattern (simple path or glob).
     * Returns a list because a glob can match multiple files.
     */
    private List<Path> resolvePathPattern(String contributorServerId, String pathPattern) {
        List<Path> matches = new ArrayList<>();

        // Remove leading "./" if present
        String normalizedPath = pathPattern.startsWith("./") ? pathPattern.substring(2) : pathPattern;
        Path serverDir = serversBaseDir.resolve(contributorServerId);

        // Check if pattern contains glob characters
        if (!normalizedPath.contains("*") && !normalizedPath.contains("?")) {
            // Simple path - resolve directly
            Path resolved = serverDir.resolve(normalizedPath);
            if (java.nio.file.Files.exists(resolved)) {
                matches.add(resolved);
            } else {
                LOG.warnf("Extension not found: %s (from server %s)", resolved, contributorServerId);
            }
            return matches;
        }

        // Glob pattern - expand it
        try {
            // Split pattern into directory and file parts
            int lastSlash = normalizedPath.lastIndexOf('/');
            Path baseDir;
            String glob;

            if (lastSlash > 0) {
                // Pattern like "lib/*.jar"
                String dirPart = normalizedPath.substring(0, lastSlash);
                glob = normalizedPath.substring(lastSlash + 1);
                baseDir = serverDir.resolve(dirPart);
            } else {
                // Pattern like "*.jar" in server root
                glob = normalizedPath;
                baseDir = serverDir;
            }

            if (!java.nio.file.Files.exists(baseDir)) {
                LOG.warnf("Base directory not found for glob pattern %s: %s (from server %s)",
                         pathPattern, baseDir, contributorServerId);
                return matches;
            }

            // Use PathMatcher to match files
            final java.nio.file.PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + glob);
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(baseDir)) {
                stream.filter(path -> matcher.matches(path.getFileName()))
                      .forEach(matches::add);
            }

            LOG.infof("Glob pattern %s expanded to %d files (from server %s)",
                     pathPattern, matches.size(), contributorServerId);

        } catch (java.io.IOException e) {
            LOG.errorf(e, "Failed to expand glob pattern: %s (from server %s)", pathPattern, contributorServerId);
        }

        return matches;
    }
}
