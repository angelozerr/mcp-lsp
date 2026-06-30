package com.redhat.mcp.languagetools.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.redhat.mcp.languagetools.PathManager;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for loading server descriptors (LSP and DAP).
 * Each server has: server.json (config) + installer.json (installation steps).
 *
 * @param <T> Server config type (LspServerConfig or DapServerConfig)
 */
public abstract class ServerDescriptorLoaderBase<T extends ServerConfigBase> {

    private static final Logger LOG = Logger.getLogger(ServerDescriptorLoaderBase.class);
    private static final String SERVER_CONFIG_FILE = "server.json";
    private static final String SERVER_EXTENSION_CONFIG_FILE = "server-extension.json";
    private static final String INSTALLER_CONFIG_FILE = "installer.json";

    protected PathManager pathManager; // Will be injected in subclasses
    protected final Gson gson = new Gson();
    protected final ObjectMapper objectMapper = new ObjectMapper();

    // No-args constructor for CDI
    protected ServerDescriptorLoaderBase() {
    }

    /**
     * Get the resource directory for this server type (e.g., "lsp", "dap").
     */
    protected abstract String getResourceDirectory();

    /**
     * Parse server configuration from JSON.
     */
    protected abstract T parseServerConfig(InputStream is, String serverId) throws IOException;

    /**
     * Load a bundled server configuration from resources.
     * Expects structure: /{resourceDir}/{serverId}/server.json and optionally /{resourceDir}/{serverId}/installer.json
     */
    public T loadBundled(String serverId) throws IOException {
        String serverPath = buildResourcePath(serverId, SERVER_CONFIG_FILE);
        String extensionPath = buildResourcePath(serverId, SERVER_EXTENSION_CONFIG_FILE);
        String installerPath = buildResourcePath(serverId, INSTALLER_CONFIG_FILE);

        // Load server.json or server-extension.json
        T config;
        InputStream serverStream = getClass().getResourceAsStream(serverPath);
        boolean isExtension = false;

        if (serverStream == null) {
            // Try server-extension.json
            serverStream = getClass().getResourceAsStream(extensionPath);
            if (serverStream == null) {
                throw new IOException("Bundled server config not found: " + serverPath + " or " + extensionPath);
            }
            isExtension = true;
            LOG.infof("Loading bundled extension config: %s", extensionPath);
        } else {
            LOG.infof("Loading bundled server config: %s", serverPath);
        }

        try (InputStream is = serverStream) {
            config = parseServerConfig(is, serverId);
            markAsExtension(config, isExtension);
        }

        // Load installer.json if present
        loadInstallerConfig(config, installerPath);

        return config;
    }

    /**
     * Load installer configuration and attach to config.
     */
    protected void loadInstallerConfig(T config, String installerPath) {
        try (InputStream is = getClass().getResourceAsStream(installerPath)) {
            if (is != null) {
                JsonNode installerConfig = objectMapper.readTree(is);
                config.setInstallerConfig(installerConfig);
                LOG.debugf("Loaded installer config for: %s", config.getId());
            }
        } catch (IOException e) {
            LOG.debugf("No installer config for %s: %s", config.getId(), e.getMessage());
        }
    }

    /**
     * Mark config as extension (optional, for LSP only).
     */
    protected void markAsExtension(T config, boolean isExtension) {
        // Default: do nothing (DAP doesn't have extensions)
        // LSP overrides this
    }

    /**
     * Load a user-defined server configuration from file system.
     */
    public T loadFromFile(Path configFile) throws IOException {
        LOG.infof("Loading server config from file: %s", configFile);

        try (InputStream is = Files.newInputStream(configFile)) {
            String serverId = configFile.getFileName().toString().replace(".json", "");
            return parseServerConfig(is, serverId);
        }
    }

    /**
     * Build resource path for a server file.
     */
    protected String buildResourcePath(String serverId, String fileName) {
        return "/" + getResourceDirectory() + "/" + serverId + "/" + fileName;
    }

    /**
     * Load all bundled server configurations.
     * Auto-discovers all server.json files in resource directory.
     *
     * @return Map of serverId -> config
     */
    public Map<String, T> loadAllBundled() {
        Map<String, T> configs = new HashMap<>();

        try {
            // Use context ClassLoader to see all resources (including from extensions in multi-module)
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            // Get all resource directories from all JARs/modules in classpath
            java.util.Enumeration<java.net.URL> resources = classLoader.getResources(getResourceDirectory());

            if (!resources.hasMoreElements()) {
                LOG.warnf("No /%s directory found in classpath", getResourceDirectory());
                return configs;
            }

            // Process each directory found (can be from multiple JARs/modules)
            while (resources.hasMoreElements()) {
                java.net.URL dirUrl = resources.nextElement();
                LOG.debugf("Scanning /%s from: %s", getResourceDirectory(), dirUrl);

                // Handle both JAR and file system paths
                java.net.URI dirUri = dirUrl.toURI();
                java.nio.file.Path dirPath;
                java.nio.file.FileSystem fs = null;

                if (dirUri.getScheme().equals("jar")) {
                    // Running from JAR - use FileSystem
                    try {
                        fs = java.nio.file.FileSystems.newFileSystem(dirUri, java.util.Collections.emptyMap());
                        dirPath = fs.getPath("/" + getResourceDirectory());
                    } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                        // FileSystem already exists, get it
                        fs = java.nio.file.FileSystems.getFileSystem(dirUri);
                        dirPath = fs.getPath("/" + getResourceDirectory());
                    }
                } else {
                    // Running from IDE/filesystem
                    dirPath = java.nio.file.Paths.get(dirUri);
                }

                // Scan for server.json files in this directory
                try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(dirPath)) {
                    final java.nio.file.FileSystem finalFs = fs;
                    entries.filter(java.nio.file.Files::isDirectory)
                           .forEach(dir -> {
                               String serverId = dir.getFileName().toString();
                               // Skip if already loaded (first one wins)
                               if (configs.containsKey(serverId)) {
                                   LOG.debugf("Skipping duplicate server: %s", serverId);
                                   return;
                               }
                               try {
                                   T config = loadBundled(serverId);
                                   configs.put(config.getId(), config);
                                   LOG.infof("Auto-discovered bundled server: %s", config.getId());
                               } catch (IOException e) {
                                   LOG.debugf("Skipping %s (no valid server.json): %s", serverId, e.getMessage());
                               }
                           });
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to list directory: %s", dirPath);
                }
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to auto-discover bundled servers");
        }

        return configs;
    }
}
