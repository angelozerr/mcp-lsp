package com.redhat.mcp.languagetools.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.mcp.languagetools.installer.descriptor.ServerInstallerDescriptor;
import com.redhat.mcp.languagetools.installer.task.InstallerTask;
import com.redhat.mcp.languagetools.installer.task.InstallerTaskRegistry;
import com.redhat.mcp.languagetools.server.ServerConfigBase;
import com.redhat.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server installer that uses task registry to execute installer.json.
 */
public class TaskRegistryInstaller implements ServerInstaller {
    private static final Logger LOG = Logger.getLogger(TaskRegistryInstaller.class);

    private final ServerConfigBase config;
    private final InstallerTaskRegistry registry;
    private final ObjectMapper objectMapper;
    private final AtomicReference<InstallationStatus> status = new AtomicReference<>(InstallationStatus.NOT_INSTALLED);
    private volatile TraceProgressIndicator progressIndicator;

    public TaskRegistryInstaller(ServerConfigBase config) {
        this.config = config;
        this.registry = new InstallerTaskRegistry();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public CompletableFuture<InstallResult> ensureInstalled(InstallerContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Parse installer.json
                ServerInstallerDescriptor descriptor = loadInstallerDescriptor();
                if (descriptor == null) {
                    throw new IllegalStateException("Failed to load installer descriptor");
                }

                TraceCollector trace = config.getTraceCollector();
                if (trace != null) {
                    trace.info("Starting installation for: " + config.getName());
                }

                // Check if already installed
                if (descriptor.getCheck() != null) {
                    InstallerTask checkTask = parseTaskNode(descriptor.getCheck());
                    if (checkTask != null && checkTask.execute(context)) {
                        // Already installed - extract command
                        String command = extractCommand(descriptor, context);

                        if (trace != null) {
                            trace.info("Server already installed");
                        }

                        status.set(InstallationStatus.ALREADY_INSTALLED);
                        return new InstallResult(context.getInstallDir(), command, InstallationStatus.ALREADY_INSTALLED);
                    }
                }

                // Not installed - run installation
                status.set(InstallationStatus.INSTALLING);

                if (trace != null) {
                    trace.info("Installing server...");
                }

                InstallerTask runTask = parseTaskNode(descriptor.getRun());
                if (runTask == null) {
                    throw new IllegalStateException("No run task defined in installer.json");
                }

                boolean success = runTask.execute(context);
                if (!success) {
                    status.set(InstallationStatus.FAILED);
                    throw new IllegalStateException("Installation failed");
                }

                // Extract command after installation
                String command = extractCommand(descriptor, context);

                status.set(InstallationStatus.INSTALLED);

                if (trace != null) {
                    trace.info("Installation completed successfully");
                }

                return new InstallResult(context.getInstallDir(), command, InstallationStatus.INSTALLED);

            } catch (TraceProgressIndicator.CancellationException e) {
                status.set(InstallationStatus.STOPPED);
                TraceCollector trace = config.getTraceCollector();
                if (trace != null) {
                    trace.error("Installation cancelled by user");
                }
                throw new RuntimeException("Installation cancelled", e);
            } catch (Exception e) {
                LOG.errorf(e, "Installation failed for %s", config.getId());
                status.set(InstallationStatus.FAILED);
                TraceCollector trace = config.getTraceCollector();
                if (trace != null) {
                    trace.error("Installation failed: " + e.getMessage());
                    // Also log the root cause if different
                    Throwable cause = e.getCause();
                    if (cause != null && cause != e) {
                        trace.error("Cause: " + cause.getMessage());
                    }
                }
                throw new RuntimeException("Installation failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public InstallationStatus getStatus() {
        return status.get();
    }

    @Override
    public void stop() {
        if (progressIndicator != null) {
            progressIndicator.cancel();
        }
        status.set(InstallationStatus.STOPPED);
    }

    private ServerInstallerDescriptor loadInstallerDescriptor() {
        try {
            JsonNode installerConfig = config.getInstallerConfig();
            if (installerConfig == null) {
                LOG.errorf("No installer configuration found for %s", config.getId());
                return null;
            }

            return objectMapper.treeToValue(installerConfig, ServerInstallerDescriptor.class);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse installer descriptor for %s", config.getId());
            return null;
        }
    }

    private InstallerTask parseTaskNode(JsonNode taskNode) {
        // Find the task type (first key in the object)
        Iterator<String> fieldNames = taskNode.fieldNames();
        if (!fieldNames.hasNext()) {
            return null;
        }

        String taskType = fieldNames.next();
        JsonNode taskConfig = taskNode.get(taskType);

        return registry.createTask(taskType, taskConfig);
    }

    /**
     * Extracts the server command from installer.json.
     * The command comes from the configureServer task.
     */
    private String extractCommand(ServerInstallerDescriptor descriptor, InstallerContext context) {
        // The command was stored in context by ConfigureServerTask
        String command = context.getVariable("SERVER_COMMAND");

        if (command == null) {
            LOG.warnf("No command configured for %s", config.getId());
        }

        return command;
    }
}
