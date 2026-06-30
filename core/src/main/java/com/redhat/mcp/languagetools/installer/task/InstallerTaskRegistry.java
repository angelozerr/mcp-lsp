package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry for installer task factories using Java SPI.
 */
public class InstallerTaskRegistry {
    private static final Logger LOG = Logger.getLogger(InstallerTaskRegistry.class);

    private final Map<String, InstallerTaskFactory> factories = new HashMap<>();

    public InstallerTaskRegistry() {
        loadFactories();
    }

    private void loadFactories() {
        ServiceLoader<InstallerTaskFactory> loader = ServiceLoader.load(InstallerTaskFactory.class);
        for (InstallerTaskFactory factory : loader) {
            String type = factory.getType();
            factories.put(type, factory);
            LOG.debugf("Registered installer task factory: %s", type);
        }
    }

    /**
     * Creates a task from JSON configuration.
     *
     * @param type Task type (e.g., "download", "fileExists")
     * @param config JSON configuration
     * @return The created task, or null if factory not found
     */
    public InstallerTask createTask(String type, JsonNode config) {
        InstallerTaskFactory factory = factories.get(type);
        if (factory == null) {
            LOG.errorf("No factory found for task type: %s", type);
            return null;
        }
        return factory.createTask(config);
    }

    /**
     * Checks if a factory exists for the given type.
     */
    public boolean hasFactory(String type) {
        return factories.containsKey(type);
    }
}
