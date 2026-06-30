package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.mcp.languagetools.installer.InstallerContext;
import com.redhat.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Task that copies a file from resources to target directory.
 */
public class CopyTask implements InstallerTask {
    private static final Logger LOG = Logger.getLogger(CopyTask.class);

    private final String name;
    private final String source;
    private final String destination;
    private final InstallerTask onSuccessTask;

    public CopyTask(String name, String source, String destination, InstallerTask onSuccessTask) {
        this.name = name;
        this.source = source;
        this.destination = destination;
        this.onSuccessTask = onSuccessTask;
    }

    @Override
    public boolean execute(InstallerContext context) {
        context.checkCanceled();

        String resolvedSource = context.resolveVariables(source);
        String resolvedDestination = context.resolveVariables(destination);

        TraceCollector trace = context.getConfig().getTraceCollector();
        if (trace != null) {
            trace.info("Copying from: " + resolvedSource + " to: " + resolvedDestination);
        }

        context.getProgress().setText("Copying " + name);
        context.getProgress().setFraction(0.0);

        try {
            Path destPath = Paths.get(resolvedDestination);
            Files.createDirectories(destPath.getParent());

            // Load resource from classpath using context ClassLoader to access all modules/extensions
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            InputStream resourceStream = classLoader.getResourceAsStream(resolvedSource.startsWith("/") ? resolvedSource.substring(1) : resolvedSource);
            if (resourceStream == null) {
                LOG.errorf("Resource not found: %s", resolvedSource);
                if (trace != null) {
                    trace.error("Resource not found: " + resolvedSource);
                }
                return false;
            }

            try (InputStream is = resourceStream) {
                Files.copy(is, destPath, StandardCopyOption.REPLACE_EXISTING);
            }

            context.getProgress().setFraction(1.0);

            if (trace != null) {
                trace.info("Copied to: " + resolvedDestination);
            }

            // Execute onSuccess task
            if (onSuccessTask != null) {
                return onSuccessTask.execute(context);
            }

            return true;

        } catch (IOException e) {
            LOG.errorf(e, "Copy failed: %s -> %s", resolvedSource, resolvedDestination);
            if (trace != null) {
                trace.error("Copy failed: " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Factory for CopyTask.
     */
    public static class Factory implements InstallerTaskFactory {
        // Lazy singleton registry to avoid circular initialization
        private static volatile InstallerTaskRegistry registry;

        public Factory() {
            // Don't create registry in constructor - would cause infinite recursion
        }

        private static InstallerTaskRegistry getRegistry() {
            if (registry == null) {
                synchronized (Factory.class) {
                    if (registry == null) {
                        registry = new InstallerTaskRegistry();
                    }
                }
            }
            return registry;
        }

        @Override
        public String getType() {
            return "copy";
        }

        @Override
        public InstallerTask createTask(JsonNode config) {
            String name = config.has("name") ? config.get("name").asText() : "Copy";
            String source = config.get("source").asText();
            String destination = config.get("destination").asText();

            // Parse onSuccess tasks
            InstallerTask onSuccessTask = null;
            if (config.has("onSuccess")) {
                JsonNode onSuccess = config.get("onSuccess");
                onSuccessTask = parseTaskNode(onSuccess);
            }

            return new CopyTask(name, source, destination, onSuccessTask);
        }

        private InstallerTask parseTaskNode(JsonNode taskNode) {
            // Find the task type (first key in the object)
            var fieldNames = taskNode.fieldNames();
            if (!fieldNames.hasNext()) {
                return null;
            }

            String taskType = fieldNames.next();
            JsonNode taskConfig = taskNode.get(taskType);

            return getRegistry().createTask(taskType, taskConfig);
        }
    }
}
