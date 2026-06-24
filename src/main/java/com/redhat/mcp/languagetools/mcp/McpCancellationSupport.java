/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.mcp.languagetools.mcp;

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Support for cancelling CompletableFuture operations based on MCP Cancellation signals.
 * Polls registered Cancellation objects and automatically cancels associated futures when requested.
 */
@ApplicationScoped
public class McpCancellationSupport {

    private static final Logger LOG = Logger.getLogger(McpCancellationSupport.class);
    private static final long POLL_INTERVAL_MS = 100; // Poll every 100ms

    private final Map<Cancellation, List<CompletableFuture<?>>> registrations = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    void onStart(@Observes StartupEvent ev) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cancellation-poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::pollCancellations, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("McpCancellationSupport started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        registrations.clear();
        LOG.info("McpCancellationSupport stopped");
    }

    /**
     * Register a CompletableFuture to be cancelled when the Cancellation is requested.
     *
     * @param cancellation the MCP cancellation object
     * @param future       the future to cancel
     */
    public void register(Cancellation cancellation, CompletableFuture<?> future) {
        if (cancellation == null || future == null) {
            return;
        }

        registrations.computeIfAbsent(cancellation, k -> new CopyOnWriteArrayList<>()).add(future);

        // Auto-cleanup when future completes
        future.whenComplete((result, error) -> unregister(cancellation, future));
    }

    /**
     * Register multiple futures for the same cancellation.
     *
     * @param cancellation the MCP cancellation object
     * @param futures      the futures to cancel
     */
    public void registerAll(Cancellation cancellation, List<? extends CompletableFuture<?>> futures) {
        if (cancellation == null || futures == null) {
            return;
        }
        futures.forEach(f -> register(cancellation, f));
    }

    /**
     * Unregister a future (called automatically when future completes).
     *
     * @param cancellation the MCP cancellation object
     * @param future       the future to unregister
     */
    private void unregister(Cancellation cancellation, CompletableFuture<?> future) {
        List<CompletableFuture<?>> futures = registrations.get(cancellation);
        if (futures != null) {
            futures.remove(future);
            if (futures.isEmpty()) {
                registrations.remove(cancellation);
            }
        }
    }

    /**
     * Poll all registered cancellations and cancel associated futures if requested.
     */
    private void pollCancellations() {
        registrations.forEach((cancellation, futures) -> {
            try {
                var result = cancellation.check();
                if (result.isRequested()) {
                    String reason = result.reason().orElse("No reason provided");
                    LOG.infof("Cancellation requested: %s - cancelling %d future(s)", reason, futures.size());

                    // Cancel all associated futures
                    futures.forEach(f -> {
                        if (!f.isDone()) {
                            f.cancel(true);
                        }
                    });

                    // Remove from registrations
                    registrations.remove(cancellation);
                }
            } catch (Exception e) {
                LOG.warnf("Error checking cancellation: %s", e.getMessage());
            }
        });
    }
}
