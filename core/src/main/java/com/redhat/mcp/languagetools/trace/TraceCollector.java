package com.redhat.mcp.languagetools.trace;

/**
 * Interface for collecting trace messages during server operations.
 * Allows installer and other components to send trace messages without
 * coupling to specific LSP/DAP trace implementations.
 */
public interface TraceCollector {

    /**
     * Send a trace message.
     *
     * @param message Message to trace
     * @param level   Trace level
     */
    void trace(String message, Level level);

    /**
     * Trace level.
     */
    enum Level {
        INFO,
        WARN,
        ERROR,
        DEBUG
    }

    /**
     * Send an info trace message.
     */
    default void info(String message) {
        trace(message, Level.INFO);
    }

    /**
     * Send a warning trace message.
     */
    default void warn(String message) {
        trace(message, Level.WARN);
    }

    /**
     * Send an error trace message.
     */
    default void error(String message) {
        trace(message, Level.ERROR);
    }
}
