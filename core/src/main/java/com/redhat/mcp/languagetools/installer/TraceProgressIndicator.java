package com.redhat.mcp.languagetools.installer;

import com.redhat.mcp.languagetools.trace.TraceCollector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress indicator that sends traces via TraceCollector.
 */
public class TraceProgressIndicator implements ProgressIndicator {
    private final TraceCollector traceCollector;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private volatile String currentText = "";
    private volatile boolean sendProgressUpdates = false;  // Only send updates when explicitly enabled
    private volatile double currentFraction = 0.0;  // Current progress fraction (0.0 to 1.0)

    public TraceProgressIndicator(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    @Override
    public void setText(String text) {
        this.currentText = text;
        // Disable progress updates when text changes (new phase)
        this.sendProgressUpdates = false;
        if (traceCollector != null) {
            traceCollector.info(text);
        }
    }

    @Override
    public void setText2(String text) {
        if (traceCollector != null) {
            traceCollector.info("  " + text);
        }
    }

    @Override
    public void setFraction(double fraction) {
        this.currentFraction = fraction;
        // Only send progress updates if explicitly enabled
        // (e.g., by ProgressIndicatorWrapper for download progress)
        if (sendProgressUpdates && traceCollector != null) {
            int percent = (int) (fraction * 100);
            traceCollector.update(currentText + " (" + percent + "%)");
        }
    }

    /**
     * Get the current progress fraction (0.0 to 1.0).
     * This is used by the UI to display a visual progress bar.
     */
    public double getFraction() {
        return currentFraction;
    }

    /**
     * Enable progress updates for this indicator.
     * Used by ProgressIndicatorWrapper to enable real-time download progress.
     */
    public void enableProgressUpdates() {
        this.sendProgressUpdates = true;
    }

    @Override
    public boolean isCanceled() {
        return canceled.get();
    }

    @Override
    public void checkCanceled() {
        if (isCanceled()) {
            throw new CancellationException("Installation cancelled");
        }
    }

    /**
     * Cancels the operation.
     */
    public void cancel() {
        canceled.set(true);
    }

    /**
     * Exception thrown when installation is cancelled.
     */
    public static class CancellationException extends RuntimeException {
        public CancellationException(String message) {
            super(message);
        }
    }
}
