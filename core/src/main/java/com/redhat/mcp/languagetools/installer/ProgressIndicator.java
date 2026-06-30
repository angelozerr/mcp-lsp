package com.redhat.mcp.languagetools.installer;

/**
 * Progress indicator for installation tasks.
 * Inspired by IntelliJ's ProgressIndicator API.
 */
public interface ProgressIndicator {
    /**
     * Sets the primary text (main progress message).
     */
    void setText(String text);

    /**
     * Sets the secondary text (detail message).
     */
    void setText2(String text);

    /**
     * Sets the progress fraction (0.0 to 1.0).
     */
    void setFraction(double fraction);

    /**
     * Checks if the operation was cancelled.
     */
    boolean isCanceled();

    /**
     * Throws CancellationException if cancelled.
     */
    void checkCanceled();
}
