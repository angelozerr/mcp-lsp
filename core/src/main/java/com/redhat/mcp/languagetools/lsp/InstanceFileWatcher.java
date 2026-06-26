package com.redhat.mcp.languagetools.lsp;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Watches instance file for changes: ${workspace}/.lsp-servers/{serverType}.json
 * Triggers callbacks when the instance appears, changes, or disappears.
 */
public class InstanceFileWatcher {

    private static final Logger LOG = Logger.getLogger(InstanceFileWatcher.class);

    private final String workspacePath;
    private final String serverType;
    private final Consumer<LspInstanceRegistry.InstanceInfo> onInstanceChanged;
    private final Runnable onInstanceRemoved;

    private WatchService watchService;
    private ExecutorService executorService;
    private volatile boolean running = false;

    /**
     * @param workspacePath       the workspace path to monitor
     * @param serverType          the server type (e.g., "lemminx", "jdtls")
     * @param onInstanceChanged   callback when a new/updated instance is detected
     * @param onInstanceRemoved   callback when the instance file is deleted or becomes invalid
     */
    public InstanceFileWatcher(String workspacePath, String serverType,
                               Consumer<LspInstanceRegistry.InstanceInfo> onInstanceChanged,
                               Runnable onInstanceRemoved) {
        this.workspacePath = workspacePath;
        this.serverType = serverType;
        this.onInstanceChanged = onInstanceChanged;
        this.onInstanceRemoved = onInstanceRemoved;
    }

    /**
     * Start watching the instance file.
     */
    public void start() throws IOException {
        if (running) {
            LOG.warnf("Instance file watcher already running for %s", serverType);
            return;
        }

        Path instancesDir = LspInstanceRegistry.getInstancesDir(workspacePath);
        if (!Files.exists(instancesDir)) {
            LOG.debugf("Instances directory does not exist yet: %s", instancesDir);
            // Could create it and watch, but for now just skip
            return;
        }

        running = true;
        executorService = Executors.newSingleThreadExecutor();
        watchService = FileSystems.getDefault().newWatchService();

        instancesDir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);

        executorService.submit(this::watchDirectory);
        LOG.infof("Watching instance file: %s", LspInstanceRegistry.getInstanceFilePath(workspacePath, serverType));
    }

    /**
     * Stop watching the instance file.
     */
    public void stop() {
        running = false;

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.debugf("Error closing watch service: %s", e.getMessage());
            }
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }

        LOG.infof("Stopped watching instance file for %s", serverType);
    }

    /**
     * Watch the .lsp-servers directory for file changes.
     */
    private void watchDirectory() {
        String expectedFileName = LspInstanceRegistry.getInstanceFileName(serverType);

        try {
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();

                    if (!filename.toString().equals(expectedFileName)) {
                        continue;
                    }

                    LOG.infof("Instance file changed: %s (%s)", filename, kind.name());

                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleInstanceRemoved();
                    } else {
                        handleInstanceChanged();
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                LOG.errorf(e, "Error watching directory");
            }
        }
    }

    /**
     * Handle instance file change - check if new instance is available.
     */
    private void handleInstanceChanged() {
        LspInstanceRegistry.InstanceInfo newInstance = LspInstanceRegistry.findInstance(workspacePath, serverType);

        if (newInstance != null) {
            LOG.infof("New/updated instance detected: %s", newInstance);
            try {
                onInstanceChanged.accept(newInstance);
            } catch (Exception e) {
                LOG.errorf(e, "Error in instance changed callback");
            }
        } else {
            LOG.debugf("Instance file changed but no valid instance found");
            handleInstanceRemoved();
        }
    }

    /**
     * Handle instance removal - file deleted or PID dead.
     */
    private void handleInstanceRemoved() {
        LOG.infof("Instance removed or became invalid for %s", serverType);
        try {
            onInstanceRemoved.run();
        } catch (Exception e) {
            LOG.errorf(e, "Error in instance removed callback");
        }
    }
}
