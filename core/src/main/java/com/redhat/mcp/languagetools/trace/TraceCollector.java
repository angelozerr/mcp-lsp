package com.redhat.mcp.languagetools.trace;

/**
 * Interface for collecting trace messages during server operations.
 * Allows installer and other components to send trace messages without
 * coupling to specific LSP/DAP trace implementations.
 */
public interface TraceCollector {

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
     * Message type - determines how the trace is displayed.
     */
    enum MessageType {
        TRACE,   // Normal trace line (default) - creates new line
        UPDATE,  // Update previous line instead of creating new one
        ERROR,   // Error message
        INFO     // Info message
    }

    /**
     * Send a trace message with specific level and type.
     *
     * @param message Message to trace
     * @param level   Trace level
     * @param type    Message type (TRACE, UPDATE, ERROR, INFO)
     */
    void trace(String message, Level level, MessageType type);

    /**
     * Send a trace message with default type (TRACE).
     *
     * @param message Message to trace
     * @param level   Trace level
     */
    default void trace(String message, Level level) {
        trace(message, level, MessageType.TRACE);
    }

    /**
     * Send an info trace message.
     */
    default void info(String message) {
        trace(message, Level.INFO, MessageType.INFO);
    }

    /**
     * Send a warning trace message.
     */
    default void warn(String message) {
        trace(message, Level.WARN, MessageType.TRACE);
    }

    /**
     * Send an error trace message.
     */
    default void error(String message) {
        trace(message, Level.ERROR, MessageType.ERROR);
    }

    /**
     * Send a debug trace message.
     */
    default void debug(String message) {
        trace(message, Level.DEBUG, MessageType.TRACE);
    }

    /**
     * Send an update message (replaces previous line instead of creating new one).
     * Useful for progress indicators.
     */
    default void update(String message) {
        trace(message, Level.INFO, MessageType.UPDATE);
    }
}
