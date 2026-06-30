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

    public TraceProgressIndicator(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    @Override
    public void setText(String text) {
        this.currentText = text;
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
        if (traceCollector != null) {
            int percent = (int) (fraction * 100);
            traceCollector.info(currentText + " (" + percent + "%)");
        }
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
