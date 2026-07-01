package com.redhat.mcp.languagetools.installer;

/**
 * Exception thrown when server installation fails.
 */
public class InstallationException extends RuntimeException {

    public InstallationException(String message) {
        super(message);
    }

    public InstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
