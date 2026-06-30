package com.redhat.mcp.languagetools.server;

import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Base class for server implementations (LSP and DAP).
 * Provides common functionality for managing server configuration and lifecycle.
 *
 * @param <T> The type of server configuration (LspServerConfig or DapServerConfig)
 */
public abstract class ServerBase<T extends ServerConfigBase> {

    private static final Logger LOG = Logger.getLogger(ServerBase.class);

    private final T config;
    protected final ExecutorService executorService;
    private volatile ServerStatus status = ServerStatus.NOT_STARTED;
    private volatile String statusMessage = null;
    private Consumer<ServerStatus> statusChangeCallback;

    protected Process serverProcess;
    private volatile boolean isReady;

    public ServerBase(T config) {
        this.config = config;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Get the server configuration.
     */
    public T getConfig() {
        return config;
    }

    /**
     * Get the current server status.
     */
    public final ServerStatus getStatus() {
        return status;
    }

    /**
     * Set a callback to be notified when server status changes.
     */
    public void setStatusChangeCallback(java.util.function.Consumer<ServerStatus> callback) {
        this.statusChangeCallback = callback;
    }

    /**
     * Update server status and notify callback if registered.
     */
   protected  void setStatus(ServerStatus newStatus) {
        ServerStatus oldStatus = this.status;
        this.status = newStatus;

        // Clear status message when stopping/stopped
        if (newStatus == ServerStatus.STOPPING || newStatus == ServerStatus.STOPPED) {
            this.statusMessage = null;
        }

        LOG.infof("LspServer.setStatus: %s -> %s (callback registered: %s)",
                oldStatus, newStatus, statusChangeCallback != null);

        if (statusChangeCallback != null && oldStatus != newStatus) {
            LOG.infof("Calling status change callback for %s: %s -> %s", config.getId(), oldStatus, newStatus);
            try {
                statusChangeCallback.accept(newStatus);
            } catch (Exception e) {
                LOG.warnf(e, "Error in status change callback for %s", config.getId());
            }
        }
    }

    public final String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        String oldMessage = this.statusMessage;
        this.statusMessage = statusMessage;

        LOG.infof("[%s] setStatusMessage called: %s -> %s (callback: %s)",
                config.getId(), oldMessage, statusMessage, statusChangeCallback != null);

        // Notify if message changed and callback is registered
        if (statusChangeCallback != null && !java.util.Objects.equals(oldMessage, statusMessage)) {
            LOG.infof("[%s] Status message changed, firing callback", config.getId());
            // Trigger status change callback to refresh UI
            statusChangeCallback.accept(this.status);
        }
    }

    /**
     * Wait until the server is ready, with a timeout.
     * Returns a CompletableFuture that completes when the server is ready.
     */
    public CompletableFuture<Void> waitUntilReady(long timeoutMs) {
        if (isReady) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            while (!isReady && (System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for server to be ready", e);
                }
            }
            if (!isReady) {
                throw new RuntimeException("Server not ready after " + timeoutMs + "ms");
            }
        }, executorService);
    }

    /**
     * Check if the language server is ready to handle requests.
     * For most servers, this is true after initialize() completes (status == RUNNING).
     * For servers like JDT.LS, this is determined by specific notifications (e.g., language/status).
     * Subclasses can override this method to provide custom readiness detection.
     */
    public final boolean isReady() {
        return isReady;
    }

    /**
     * Set the ready state of the language server.
     * Called by subclasses when they detect the server is ready (e.g., JdtLsServer on language/status).
     */
    public final void setReady(boolean ready) {
        this.isReady = ready;
    }

    /**
     * Stop the server.
     */
    public void stop() {
        if (serverProcess != null && serverProcess.isAlive()) {
            LOG.infof("Stopping server: %s", config.getId());
            setStatus(ServerStatus.STOPPING);
            serverProcess.destroy();
            setStatus(ServerStatus.STOPPED);
        }
    }
}
