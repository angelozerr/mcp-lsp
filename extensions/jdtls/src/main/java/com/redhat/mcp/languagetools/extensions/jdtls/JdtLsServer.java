package com.redhat.mcp.languagetools.extensions.jdtls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.LspServerContext;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Custom LSP server for Eclipse JDT.LS.
 * Handles JDT.LS-specific startup logic and readiness detection.
 * Similar to vscode-java's javaServerStarter.ts
 */
public class JdtLsServer extends LspServer {

    private static final Logger LOG = Logger.getLogger(JdtLsServer.class);

    private JdtLsLanguageClient jdtClient;

    public JdtLsServer(LspServerConfig config, LspServerContext context) {
        super(config, context);
    }

    /**
     * Prepare initialization options for JDT.LS.
     * Collects bundles from contributes.jdtls and passes them via initializationOptions.bundles.
     */
    @Override
    protected Object prepareInitializationOptions() {
        Map<String, Object> options = new HashMap<>();

        // Start with config-defined initialization options
        var config = super.getConfig();
        if (config.getInitializationOptions() != null && !config.getInitializationOptions().isEmpty()) {
            options.putAll(config.getInitializationOptions());
        }

        // Add required JDT.LS settings if not already present
        if (!options.containsKey("settings")) {
            Map<String, Object> settings = new HashMap<>();
            Map<String, Object> javaSettings = new HashMap<>();
            settings.put("java", javaSettings);
            options.put("settings", settings);
        }

        // Add extended client capabilities
        if (!options.containsKey("extendedClientCapabilities")) {
            Map<String, Object> extendedCaps = new HashMap<>();
            extendedCaps.put("classFileContentsSupport", true);
            extendedCaps.put("shouldLanguageServerExitOnShutdown", true);
            options.put("extendedClientCapabilities", extendedCaps);
        }

        // Collect bundles from all servers that contribute to jdtls
        List<String> bundlePaths = collectBundles();
        if (!bundlePaths.isEmpty()) {
            options.put("bundles", bundlePaths);
            LOG.infof("Passing %d bundles to JDT.LS via initializationOptions", bundlePaths.size());
        }

        return options.isEmpty() ? null : options;
    }

    /**
     * Collect all bundles from contributes.jdtls across all server configs.
     * Returns absolute paths to bundle JARs.
     */
    private List<String> collectBundles() {
        List<String> bundlePaths = new ArrayList<>();

        // First, ensure all bundles are extracted from resources to filesystem
        ensureBundlesExtracted();

        for (LspServerConfig serverConfig : allServerConfigs) {
            if (serverConfig.getContributes() == null) {
                continue;
            }

            // Read contributes.jdtls
            JsonElement jdtlsContrib = serverConfig.getContributes().getContribution("jdtls");
            if (jdtlsContrib == null || !jdtlsContrib.isJsonObject()) {
                continue;
            }

            JsonObject jdtlsObj = jdtlsContrib.getAsJsonObject();
            if (!jdtlsObj.has("bundles") || !jdtlsObj.get("bundles").isJsonArray()) {
                continue;
            }

            // Resolve bundle paths
            JsonArray bundles = jdtlsObj.getAsJsonArray("bundles");
            for (JsonElement bundleElem : bundles) {
                String bundlePattern = bundleElem.getAsString();
                List<String> resolved = resolveBundlePaths(serverConfig.getId(), bundlePattern);
                bundlePaths.addAll(resolved);
            }
        }

        return bundlePaths;
    }

    /**
     * Resolve bundle paths, supporting wildcards like "plugins/*.jar".
     * Tries resources first (bundled), then serverHome (installed).
     */
    private List<String> resolveBundlePaths(String contributorServerId, String bundlePattern) {
        List<String> resolved = new ArrayList<>();

        // Try resources first (bundled in JAR)
        resolved.addAll(resolveBundlesFromResources(contributorServerId, bundlePattern));

        // If not found in resources, try serverHome (installed)
        if (resolved.isEmpty()) {
            resolved.addAll(resolveBundlesFromServerHome(contributorServerId, bundlePattern));
        }

        return resolved;
    }

    /**
     * Resolve bundles from resources (bundled in JAR).
     */
    private List<String> resolveBundlesFromResources(String contributorServerId, String bundlePattern) {
        List<String> resolved = new ArrayList<>();

        // Resolve path (remove leading ./)
        String normalizedPath = bundlePattern.startsWith("./") ? bundlePattern.substring(2) : bundlePattern;
        String resourcePath = "/lsp/" + contributorServerId + "/" + normalizedPath;

        LOG.debugf("Trying to resolve bundle from resources: %s", resourcePath);

        // For now, we can't easily list resources in a JAR with wildcards
        // So we'll skip this and rely on serverHome resolution
        // TODO: Implement resource listing if needed

        return resolved;
    }

    /**
     * Resolve bundles from serverHome (installed directory).
     */
    private List<String> resolveBundlesFromServerHome(String contributorServerId, String bundlePattern) {
        List<String> resolved = new ArrayList<>();

        // Get contributor server's home directory
        Path contributorHome = getContributorServerHome(contributorServerId);
        if (contributorHome == null || !Files.exists(contributorHome)) {
            LOG.debugf("Contributor server home not found: %s (looking for installed bundles)", contributorServerId);
            return resolved;
        }

        // Resolve path (remove leading ./)
        String normalizedPath = bundlePattern.startsWith("./") ? bundlePattern.substring(2) : bundlePattern;

        // Check for wildcards
        if (normalizedPath.contains("*") || normalizedPath.contains("?")) {
            // Use Java glob pattern matching
            // Extract the directory to search in
            int lastSlash = normalizedPath.lastIndexOf('/');
            String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
            String filePattern = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;

            Path searchDir = dirPart.isEmpty() ? contributorHome : contributorHome.resolve(dirPart);

            // Build glob pattern for matching (just the filename part)
            String globPattern = "glob:" + filePattern;

            LOG.debugf("Expanding glob pattern: %s in directory: %s", globPattern, searchDir);

            try {
                java.nio.file.PathMatcher matcher = searchDir.getFileSystem().getPathMatcher(globPattern);

                if (Files.exists(searchDir) && Files.isDirectory(searchDir)) {
                    try (Stream<Path> files = Files.list(searchDir)) {
                        files.filter(p -> matcher.matches(p.getFileName()))
                             .forEach(p -> {
                                 resolved.add(p.toAbsolutePath().toString());
                                 LOG.infof("Resolved bundle: %s (from %s)", p, contributorServerId);
                             });
                    }
                } else {
                    LOG.warnf("Bundle directory not found: %s", searchDir);
                }
            } catch (IOException e) {
                LOG.warnf("Failed to expand glob pattern %s: %s", normalizedPath, e.getMessage());
            }
        } else {
            // Simple path
            Path bundlePath = contributorHome.resolve(normalizedPath);
            if (Files.exists(bundlePath)) {
                resolved.add(bundlePath.toAbsolutePath().toString());
                LOG.debugf("Resolved bundle: %s (from %s)", bundlePath, contributorServerId);
            } else {
                LOG.warnf("Bundle not found: %s (from server %s)", bundlePath, contributorServerId);
            }
        }

        return resolved;
    }

    /**
     * Get the server home directory for a contributor server.
     */
    private Path getContributorServerHome(String serverId) {
        // Server homes are in ~/.mcp-languagetools/lsp/{serverId}/
        return pathManager.getLspServerHome(serverId);
    }

    /**
     * Simple wildcard matching (supports * only).
     */
    private boolean matchesPattern(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return fileName.matches(regex);
    }

    /**
     * Ensure all bundles from contributes.jdtls are extracted from resources to filesystem.
     * This is needed because JDT.LS requires filesystem paths for bundles.
     */
    private void ensureBundlesExtracted() {
        for (LspServerConfig serverConfig : allServerConfigs) {
            if (serverConfig.getContributes() == null) {
                continue;
            }

            // Read contributes.jdtls
            JsonElement jdtlsContrib = serverConfig.getContributes().getContribution("jdtls");
            if (jdtlsContrib == null || !jdtlsContrib.isJsonObject()) {
                continue;
            }

            JsonObject jdtlsObj = jdtlsContrib.getAsJsonObject();
            if (!jdtlsObj.has("bundles") || !jdtlsObj.get("bundles").isJsonArray()) {
                continue;
            }

            // Extract bundles for this server
            JsonArray bundles = jdtlsObj.getAsJsonArray("bundles");
            for (JsonElement bundleElem : bundles) {
                String bundlePattern = bundleElem.getAsString();
                extractBundleFromResources(serverConfig.getId(), bundlePattern);
            }
        }
    }

    /**
     * Extract a bundle (or bundle directory) from resources to filesystem.
     */
    private void extractBundleFromResources(String serverId, String bundlePattern) {
        // Normalize path (remove leading ./)
        String normalizedPath = bundlePattern.startsWith("./") ? bundlePattern.substring(2) : bundlePattern;

        // Target directory on filesystem
        Path targetServerHome = pathManager.getLspServerHome(serverId);

        try {
            Files.createDirectories(targetServerHome);

            // Resource path
            String resourcePath = "/lsp/" + serverId + "/" + normalizedPath;

            // Check if it's a directory pattern (e.g., "plugins/*.jar" -> extract "plugins/" directory)
            if (normalizedPath.contains("*")) {
                // Extract the directory part
                int lastSlash = normalizedPath.lastIndexOf('/');
                String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
                if (!dirPart.isEmpty()) {
                    extractResourceDirectory("/lsp/" + serverId + "/" + dirPart, targetServerHome.resolve(dirPart));
                }
            } else if (normalizedPath.endsWith("/") || !normalizedPath.contains(".")) {
                // It's a directory
                extractResourceDirectory(resourcePath, targetServerHome.resolve(normalizedPath));
            } else {
                // It's a single file
                extractResourceFile(resourcePath, targetServerHome.resolve(normalizedPath));
            }

        } catch (IOException e) {
            LOG.warnf("Failed to extract bundle %s from resources: %s", bundlePattern, e.getMessage());
        }
    }

    /**
     * Extract a single file from resources to filesystem.
     */
    private void extractResourceFile(String resourcePath, Path targetPath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.debugf("Resource not found (might be in filesystem): %s", resourcePath);
                return;
            }

            Files.createDirectories(targetPath.getParent());
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            LOG.infof("Extracted resource: %s -> %s", resourcePath, targetPath);
        }
    }

    /**
     * Extract a directory from resources to filesystem.
     */
    private void extractResourceDirectory(String resourceDirPath, Path targetDir) throws IOException {
        LOG.infof("Extracting resource directory: %s -> %s", resourceDirPath, targetDir);

        Files.createDirectories(targetDir);

        // List resources in this directory
        // This is tricky because we need to handle both filesystem and JAR
        java.net.URL dirUrl = getClass().getResource(resourceDirPath);
        if (dirUrl == null) {
            LOG.debugf("Resource directory not found: %s", resourceDirPath);
            return;
        }

        try {
            if (dirUrl.toURI().getScheme().equals("jar")) {
                // Running from JAR
                extractFromJar(resourceDirPath, targetDir);
            } else {
                // Running from filesystem (IDE)
                Path sourcePath = Paths.get(dirUrl.toURI());
                copyDirectory(sourcePath, targetDir);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract resource directory %s: %s", resourceDirPath, e.getMessage());
        }
    }

    /**
     * Extract files from JAR resources.
     */
    private void extractFromJar(String resourcePath, Path targetDir) throws IOException {
        // Use the JAR file system to list and copy files
        java.net.URL jarUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(new java.io.File(jarUrl.toURI()))) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
            String prefix = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            if (!prefix.endsWith("/")) {
                prefix += "/";
            }

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(prefix) && !entry.isDirectory()) {
                    String relativePath = entry.getName().substring(prefix.length());
                    Path targetFile = targetDir.resolve(relativePath);

                    Files.createDirectories(targetFile.getParent());
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        LOG.debugf("Extracted: %s", targetFile);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract from JAR: %s", e.getMessage());
        }
    }

    /**
     * Copy directory recursively (for filesystem mode).
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        LOG.debugf("Copied: %s", dest);
                    }
                } catch (IOException e) {
                    LOG.warnf("Failed to copy %s: %s", src, e.getMessage());
                }
            });
        }
    }

    /**
     * Create a JDT.LS-specific language client that handles language/status notifications.
     */
    @Override
    protected LanguageClient createLanguageClient() {
        jdtClient = new JdtLsLanguageClient(this);
        return jdtClient;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return super.initialize()
                .thenRun(() -> {
                    // Override parent behavior: JDT.LS is NOT ready immediately after initialize
                    // We need to wait for language/status notification with "ServiceReady"
                    setReady(false);
                    LOG.infof("JDT.LS initialized for workspace: %s (waiting for ServiceReady notification)", workspaceRoot);
                    // The language/status notifications will be handled by JdtLsLanguageClient
                });
    }

    /**
     * Get the JDT.LS-specific client.
     */
    public JdtLsLanguageClient getJdtClient() {
        return jdtClient;
    }

    /**
     * Build the JDT.LS command with custom arguments.
     * Similar to vscode-java's prepareParams (javaServerStarter.ts).
     *
     * Command structure:
     * java [jvm-args] -jar launcher.jar [osgi-args] -configuration [config-dir] -data [workspace]
     */
    @Override
    protected java.util.List<String> buildCommand() throws java.io.IOException {
        java.util.List<String> params = new java.util.ArrayList<>();

        // 1. Java executable
        String javaHome = System.getProperty("java.home");
        String javaBin = java.nio.file.Paths.get(javaHome, "bin", "java").toString();
        params.add(javaBin);

        // 2. Java module system arguments (required for Java 9+)
        params.add("--add-modules=ALL-SYSTEM");
        params.add("--add-opens");
        params.add("java.base/java.util=ALL-UNNAMED");
        params.add("--add-opens");
        params.add("java.base/java.lang=ALL-UNNAMED");
        params.add("--add-opens");
        params.add("java.base/sun.nio.fs=ALL-UNNAMED");

        // 3. VM arguments from config (e.g., heap size)
        addVMArgs(params);

        // 4. Default arguments if not already present
        addDefaultVMArgsIfMissing(params);

        // 5. Find and add launcher JAR
        addLauncherJar(params);

        // 6. Eclipse/OSGi configuration directory
        params.add("-configuration");
        params.add(getConfigurationDirectory().toString());

        // 7. Eclipse application parameters
        params.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        params.add("-Dosgi.bundles.defaultStartLevel=4");
        params.add("-Declipse.product=org.eclipse.jdt.ls.core.product");

        // 9. Workspace data directory
        params.add("-data");
        params.add(workspaceDataDir.toString());

        LOG.infof("JDT.LS command: %s", String.join(" ", params));
        return params;
    }

    /**
     * Add VM arguments from workspace configuration (java.jdt.ls.vmargs).
     * Similar to vscode-java's parseVMargs().
     */
    private void addVMArgs(java.util.List<String> params) {
        if (workspaceConfiguration == null) {
            LOG.debug("No workspace configuration available, skipping vmargs");
            return;
        }

        String vmargs = workspaceConfiguration.getString("java.jdt.ls.vmargs");
        if (vmargs == null || vmargs.trim().isEmpty()) {
            LOG.debug("No java.jdt.ls.vmargs configured");
            return;
        }

        // Parse vmargs string - handle quoted arguments
        java.util.List<String> parsedArgs = parseVMArgsString(vmargs);
        params.addAll(parsedArgs);

        LOG.infof("Added VM args from java.jdt.ls.vmargs: %s", vmargs);
    }

    /**
     * Parse VM arguments string into a list.
     * Handles quotes: "arg with spaces" or -Dfoo="bar baz"
     */
    private java.util.List<String> parseVMArgsString(String vmargs) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < vmargs.length(); i++) {
            char c = vmargs.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    /**
     * Add default VM arguments if not already present.
     */
    private void addDefaultVMArgsIfMissing(java.util.List<String> params) {
        String paramsStr = String.join(" ", params);

        // Disable VM installations detection job
        if (!paramsStr.contains("-DDetectVMInstallationsJob.disabled")) {
            params.add("-DDetectVMInstallationsJob.disabled=true");
        }

        // File encoding (default to UTF-8)
        if (!paramsStr.contains("-Dfile.encoding")) {
            params.add("-Dfile.encoding=UTF-8");
        }

        // Disable JVM logging
        if (!paramsStr.contains("-Xlog")) {
            params.add("-Xlog:disable");
        }

        // Default heap size if not specified
        if (!paramsStr.contains("-Xmx")) {
            params.add("-Xmx1G");
        }
        if (!paramsStr.contains("-Xms")) {
            params.add("-Xms100m");
        }
    }

    /**
     * Find the Eclipse Equinox launcher JAR and add to params.
     */
    private void addLauncherJar(java.util.List<String> params) throws java.io.IOException {
        java.nio.file.Path pluginsDir = serverHome.resolve("plugins");

        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pluginsDir, 1)) {
            java.util.Optional<java.nio.file.Path> launcher = files
                .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .findFirst();

            if (launcher.isPresent()) {
                params.add("-jar");
                params.add(launcher.get().toString());
            } else {
                throw new java.io.IOException("Could not find Eclipse Equinox launcher JAR in " + pluginsDir);
            }
        }
    }

    /**
     * Get the configuration directory based on OS.
     * Similar to vscode-java's configDir selection (no syntax server support).
     */
    private java.nio.file.Path getConfigurationDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        String configDir;

        if (os.contains("win")) {
            configDir = "config_win";
        } else if (os.contains("mac")) {
            configDir = "config_mac";
        } else {
            configDir = "config_linux";
        }

        return serverHome.resolve(configDir);
    }
}
