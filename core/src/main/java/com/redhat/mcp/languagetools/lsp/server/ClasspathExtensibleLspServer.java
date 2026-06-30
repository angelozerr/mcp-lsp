package com.redhat.mcp.languagetools.lsp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redhat.mcp.languagetools.lsp.DocumentSelector;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic LSP server that supports classpath extensions.
 * Used for servers like MicroProfile LS, Lemminx, etc. that can be extended by adding JARs to their classpath.
 *
 * Any server can receive contributions with the format:
 * {
 *   "classpath": ["./server/extension.jar"],
 *   "documentSelector": [{"language": "xml"}]
 * }
 */
public class ClasspathExtensibleLspServer extends LspServer {

    private static final Logger LOG = Logger.getLogger(ClasspathExtensibleLspServer.class);

    public ClasspathExtensibleLspServer(LspServerConfig config, LspServerContext context) {
        super(config, context);
    }

    /**
     * Build command with classpath contributions added.
     * Expects the base command to use -jar or -cp, and injects extensions.
     */
    @Override
    protected List<String> buildCommand() throws IOException {
        var config = super.getConfig();
        LOG.infof("ClasspathExtensibleLspServer.buildCommand() called for %s", config.getId());

        // Get base command from config
        List<String> baseCommand = super.buildCommand();
        LOG.infof("Base command for %s: %s", config.getId(), String.join(" ", baseCommand));

        // Collect classpath contributions for this server
        List<Path> classpathExtensions = collectClasspathExtensions();
        LOG.infof("Collected %d classpath extensions for %s", classpathExtensions.size(), config.getId());

        if (classpathExtensions.isEmpty()) {
            // No extensions, return base command as-is
            LOG.infof("No classpath extensions, returning base command for %s", config.getId());
            return baseCommand;
        }

        // Inject extensions into classpath
        LOG.infof("Injecting %d extensions into %s classpath", classpathExtensions.size(), config.getId());
        return injectClasspathExtensions(baseCommand, classpathExtensions);
    }

    /**
     * Collect all classpath contributions to this server.
     */
    private List<Path> collectClasspathExtensions() {
        List<Path> extensions = new ArrayList<>();

        if (extensionManager == null) {
            return extensions;
        }

        var config = super.getConfig();
        Map<String, JsonElement> contributions = extensionManager.getContributionsFor(config.getId());

        for (Map.Entry<String, JsonElement> entry : contributions.entrySet()) {
            String contributorId = entry.getKey();
            JsonElement contribution = entry.getValue();

            if (contribution.isJsonObject()) {
                JsonObject contribObj = contribution.getAsJsonObject();

                // Extract classpath
                if (contribObj.has("classpath")) {
                    JsonArray jarsArray = contribObj.getAsJsonArray("classpath");

                    List<String> jarPaths = new ArrayList<>();
                    jarsArray.forEach(el -> jarPaths.add(el.getAsString()));

                    // Register parent relationship: contributor is an extension of this server
                    extensionManager.registerParentServer(contributorId, config.getId());

                    // Extract resources from JAR if contributor has no installer
                    ensureContributorResourcesExtracted(contributorId, jarPaths);

                    List<Path> resolvedJars = extensionManager.resolveExtensionPaths(contributorId, jarPaths);
                    extensions.addAll(resolvedJars);

                    LOG.infof("Added %d classpath entries from %s to %s",
                             resolvedJars.size(), contributorId, config.getId());
                }

                // Merge documentSelector
                if (contribObj.has("documentSelector")) {
                    mergeDocumentSelector(contribObj.getAsJsonArray("documentSelector"));
                }
            }
        }

        return extensions;
    }

    /**
     * Inject jarExtensions into the command's classpath.
     * Handles both -jar and -cp styles.
     */
    private List<String> injectClasspathExtensions(List<String> baseCommand, List<Path> extensions) {
        List<String> newCommand = new ArrayList<>();
        String separator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";

        boolean foundClasspath = false;

        for (int i = 0; i < baseCommand.size(); i++) {
            String arg = baseCommand.get(i);

            // Case 1: -cp or -classpath
            if (arg.equals("-cp") || arg.equals("-classpath")) {
                newCommand.add(arg);
                i++;
                if (i < baseCommand.size()) {
                    // Append extensions to existing classpath
                    String existingCp = baseCommand.get(i);
                    String extensionsCp = buildExtensionsClasspath(extensions, separator);
                    newCommand.add(existingCp + separator + extensionsCp);
                    foundClasspath = true;
                }
                continue;
            }

            // Case 2: -jar (convert to -cp)
            if (arg.equals("-jar") && i + 1 < baseCommand.size()) {
                String jarPath = baseCommand.get(i + 1);
                String extensionsCp = buildExtensionsClasspath(extensions, separator);

                newCommand.add("-cp");
                newCommand.add(jarPath + separator + extensionsCp);
                i++; // Skip the jar path
                foundClasspath = true;
                continue;
            }

            newCommand.add(arg);
        }

        var config = super.getConfig();
        if (!foundClasspath) {
            LOG.warnf("Could not inject classpath contributions into %s command (no -cp or -jar found)", config.getId());
            return baseCommand;
        }

        LOG.infof("Injected %d classpath entries into %s", extensions.size(), config.getId());
        return newCommand;
    }

    /**
     * Build classpath string from extension paths.
     */
    private String buildExtensionsClasspath(List<Path> extensions, String separator) {
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) {
                cp.append(separator);
            }
            cp.append(extensions.get(i).toString());
        }
        return cp.toString();
    }

    /**
     * Merge additional document selectors from extensions into the server config.
     */
    private void mergeDocumentSelector(JsonArray extensionSelectors) {
        var config = super.getConfig();
        List<DocumentSelector> currentSelectors = config.getDocumentSelector();

        extensionSelectors.forEach(el -> {
            if (el.isJsonObject()) {
                JsonObject selectorObj = el.getAsJsonObject();
                DocumentSelector selector = new DocumentSelector();

                if (selectorObj.has("language")) {
                    selector.setLanguage(selectorObj.get("language").getAsString());
                }
                if (selectorObj.has("scheme")) {
                    selector.setScheme(selectorObj.get("scheme").getAsString());
                }
                if (selectorObj.has("pattern")) {
                    selector.setPattern(selectorObj.get("pattern").getAsString());
                }

                currentSelectors.add(selector);
                LOG.infof("Merged documentSelector into %s: %s", config.getId(), selector);
            }
        });
    }

    /**
     * Ensure contributor resources are extracted from JAR if no installer exists.
     * Similar to how JdtLsServer extracts bundles.
     */
    private void ensureContributorResourcesExtracted(String contributorId, List<String> jarPaths) {
        // Check if contributor has an installer - if yes, skip extraction (installer handles it)
        // We need access to all configs to check - for now, extract if directory doesn't exist
        Path contributorHome = pathManager.getLspServerHome(contributorId);

        // No installer - extract resources for each jar pattern
        for (String jarPath : jarPaths) {
            extractClasspathResourceFromJar(contributorId, jarPath);
        }
    }

    /**
     * Extract classpath resource from JAR to filesystem.
     * Adapted from JdtLsServer.extractBundleFromResources()
     */
    private void extractClasspathResourceFromJar(String contributorId, String jarPattern) {
        String normalizedPath = jarPattern.startsWith("./") ? jarPattern.substring(2) : jarPattern;
        Path targetServerHome = pathManager.getLspServerHome(contributorId);

        try {
            java.nio.file.Files.createDirectories(targetServerHome);

            String resourcePath = "/lsp/" + contributorId + "/" + normalizedPath;

            // Check if it's a directory pattern (e.g., "lib/*.jar" -> extract "lib/" directory)
            if (normalizedPath.contains("*")) {
                int lastSlash = normalizedPath.lastIndexOf('/');
                String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
                if (!dirPart.isEmpty()) {
                    extractResourceDirectory("/lsp/" + contributorId + "/" + dirPart, targetServerHome.resolve(dirPart));
                }
            } else if (normalizedPath.endsWith("/") || !normalizedPath.contains(".")) {
                extractResourceDirectory(resourcePath, targetServerHome.resolve(normalizedPath));
            } else {
                extractResourceFile(resourcePath, targetServerHome.resolve(normalizedPath));
            }
        } catch (java.io.IOException e) {
            LOG.warnf("Failed to extract classpath resource %s from %s: %s", jarPattern, contributorId, e.getMessage());
        }
    }

    /**
     * Extract a single file from JAR resources to filesystem.
     */
    private void extractResourceFile(String resourcePath, Path targetPath) throws java.io.IOException {
        try (java.io.InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warnf("Resource not found: %s", resourcePath);
                return;
            }
            java.nio.file.Files.createDirectories(targetPath.getParent());
            java.nio.file.Files.copy(is, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.infof("Extracted resource: %s -> %s", resourcePath, targetPath);
        }
    }

    /**
     * Extract a directory from JAR resources to filesystem.
     */
    private void extractResourceDirectory(String resourceDirPath, Path targetDir) throws java.io.IOException {
        // Try to extract from JAR using filesystem walk
        try {
            java.net.URL resourceUrl = getClass().getResource(resourceDirPath);
            if (resourceUrl == null) {
                LOG.warnf("Resource directory not found: %s", resourceDirPath);
                return;
            }

            java.net.URI uri = resourceUrl.toURI();
            Path sourcePath;

            if (uri.getScheme().equals("jar")) {
                // Extract from JAR file
                java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap());
                sourcePath = fs.getPath(resourceDirPath);
                copyDirectory(sourcePath, targetDir);
                fs.close();
            } else {
                // Regular file system
                sourcePath = java.nio.file.Paths.get(uri);
                copyDirectory(sourcePath, targetDir);
            }
        } catch (java.net.URISyntaxException e) {
            LOG.errorf(e, "Invalid URI for resource directory: %s", resourceDirPath);
        }
    }

    /**
     * Recursively copy directory contents.
     */
    private void copyDirectory(Path source, Path target) throws java.io.IOException {
        java.nio.file.Files.createDirectories(target);

        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath).toString());
                    if (java.nio.file.Files.isDirectory(sourcePath)) {
                        java.nio.file.Files.createDirectories(targetPath);
                    } else {
                        java.nio.file.Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        LOG.debugf("Copied: %s -> %s", sourcePath, targetPath);
                    }
                } catch (java.io.IOException e) {
                    LOG.warnf(e, "Failed to copy: %s", sourcePath);
                }
            });
        }

        LOG.infof("Extracted directory: %s -> %s", source, target);
    }
}
