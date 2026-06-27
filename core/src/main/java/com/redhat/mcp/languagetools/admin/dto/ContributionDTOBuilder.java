package com.redhat.mcp.languagetools.admin.dto;

import com.google.gson.Gson;
import com.redhat.mcp.languagetools.PathManager;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builder utility to convert LspServerConfig contributions to ContributionDTO list.
 * Centralizes the logic to avoid duplication across AdminResource, LspServerControlResource, and AdminWebSocketEndpoint.
 * Resolves patterns (*.jar) to actual file paths.
 */
@ApplicationScoped
public class ContributionDTOBuilder {

    private static final Logger LOG = Logger.getLogger(ContributionDTOBuilder.class);

    private final Gson gson = new Gson();

    @Inject
    PathManager pathManager;

    /**
     * Parse contributions from config into a Map structure.
     * Resolves path patterns (*.jar) to actual file paths.
     *
     * Returns Map<targetServerId, Map<contributionType, List<?>>>
     * Example: {"jdtls": {"bundles": ["/path/to/plugin.jar"]}}
     */
    public Map<String, Map<String, List<?>>> buildContributions(LspServerConfig config) {
        Map<String, Map<String, List<?>>> contributions = new HashMap<>();

        if (config.getContributes() != null && config.getContributes().getContributions() != null) {
            config.getContributes().getContributions().forEach((targetServerId, contributionData) -> {
                // Parse contribution data (JSON) into Map<String, List<?>>
                Map<String, List<?>> data = new HashMap<>();

                if (contributionData != null && contributionData.isJsonObject()) {
                    var obj = contributionData.getAsJsonObject();

                    // For each property in the JSON object (e.g., "bundles", "bindRequest", "classpath")
                    obj.entrySet().forEach(entry -> {
                        String key = entry.getKey();
                        if (entry.getValue().isJsonArray()) {
                            // Use Gson to convert JsonArray to List<?> (handles both strings and objects)
                            List<?> values = gson.fromJson(entry.getValue(), List.class);

                            // Resolve patterns for path-based contributions (bundles, classpath)
                            if (shouldResolvePatterns(key)) {
                                values = resolvePatterns(config.getId(), values);
                            }

                            data.put(key, values);
                        }
                    });
                }

                contributions.put(targetServerId, data);
            });
        }
        return contributions;
    }

    /**
     * Check if this contribution type should have patterns resolved.
     */
    private boolean shouldResolvePatterns(String contributionType) {
        // Resolve patterns for path-based contributions
        return "bundles".equals(contributionType) || "classpath".equals(contributionType);
    }

    /**
     * Resolve path patterns (e.g., "plugins/*.jar") to actual file paths.
     * Similar to JdtLsServer.resolveBundlesFromServerHome().
     */
    private List<?> resolvePatterns(String contributorServerId, List<?> patterns) {
        List<String> resolved = new ArrayList<>();

        // Get contributor server's home directory
        Path contributorHome = getContributorServerHome(contributorServerId);
        if (contributorHome == null || !Files.exists(contributorHome)) {
            LOG.debugf("Contributor server home not found: %s (returning patterns as-is)", contributorServerId);
            return patterns; // Return original patterns if server home doesn't exist
        }

        for (Object patternObj : patterns) {
            if (!(patternObj instanceof String pattern)) {
                continue; // Skip non-string patterns
            }

            // Resolve path (remove leading ./)
            String normalizedPath = pattern.startsWith("./") ? pattern.substring(2) : pattern;

            // Check for wildcards
            if (normalizedPath.contains("*") || normalizedPath.contains("?")) {
                resolved.addAll(expandGlobPattern(contributorHome, normalizedPath, contributorServerId));
            } else {
                // Simple path without wildcards
                Path filePath = contributorHome.resolve(normalizedPath);
                if (Files.exists(filePath)) {
                    resolved.add(filePath.toAbsolutePath().toString());
                } else {
                    // Keep original pattern if file doesn't exist
                    resolved.add(pattern);
                    LOG.debugf("File not found: %s (from %s)", filePath, contributorServerId);
                }
            }
        }

        return resolved;
    }

    /**
     * Expand a glob pattern like "plugins/*.jar" to actual file paths.
     */
    private List<String> expandGlobPattern(Path contributorHome, String normalizedPath, String contributorServerId) {
        List<String> resolved = new ArrayList<>();

        // Extract the directory to search in
        int lastSlash = normalizedPath.lastIndexOf('/');
        String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
        String filePattern = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;

        Path searchDir = dirPart.isEmpty() ? contributorHome : contributorHome.resolve(dirPart);

        // Build glob pattern for matching (just the filename part)
        String globPattern = "glob:" + filePattern;

        LOG.debugf("Expanding glob pattern: %s in directory: %s", globPattern, searchDir);

        try {
            PathMatcher matcher = searchDir.getFileSystem().getPathMatcher(globPattern);

            if (Files.exists(searchDir) && Files.isDirectory(searchDir)) {
                try (Stream<Path> files = Files.list(searchDir)) {
                    files.filter(p -> matcher.matches(p.getFileName()))
                         .forEach(p -> {
                             resolved.add(p.toAbsolutePath().toString());
                             LOG.debugf("Resolved file: %s (from %s)", p, contributorServerId);
                         });
                }
            } else {
                LOG.debugf("Directory not found: %s (keeping pattern as-is)", searchDir);
                resolved.add(normalizedPath); // Keep original pattern if dir doesn't exist
            }
        } catch (IOException e) {
            LOG.warnf("Failed to expand glob pattern %s: %s (keeping pattern as-is)", normalizedPath, e.getMessage());
            resolved.add(normalizedPath); // Keep original pattern on error
        }

        return resolved;
    }

    /**
     * Get the server home directory for a contributor server.
     */
    private Path getContributorServerHome(String contributorServerId) {
        return pathManager.getLspServersDir().resolve(contributorServerId);
    }
}
