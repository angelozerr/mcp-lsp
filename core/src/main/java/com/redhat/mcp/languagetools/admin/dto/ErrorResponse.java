package com.redhat.mcp.languagetools.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Common error response format for all admin endpoints (LSP, DAP, MCP).
 * Provides consistent error formatting with message and stack trace.
 */
@RegisterForReflection
public class ErrorResponse {
    public String message;      // Short error message (first line)
    public String type;         // Full exception type (e.g., java.lang.NullPointerException)
    public String stackTrace;   // Full stack trace (for folding in UI)

    public ErrorResponse(String message, String type, String stackTrace) {
        this.message = message;
        this.type = type;
        this.stackTrace = stackTrace;
    }

    /**
     * Create an ErrorResponse from an exception.
     */
    public static ErrorResponse fromException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getName();
        }

        // Get full stack trace
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement frame : e.getStackTrace()) {
            stack.append("  at ").append(frame.toString()).append("\n");
        }

        return new ErrorResponse(
            message,
            e.getClass().getName(),
            stack.toString()
        );
    }

    // Legacy constructor for backward compatibility
    public ErrorResponse(String error) {
        this.message = error;
        this.type = "Error";
        this.stackTrace = "";
    }
}
