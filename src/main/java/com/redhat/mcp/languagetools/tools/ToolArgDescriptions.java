package com.redhat.mcp.languagetools.tools;

/**
 * Centralized descriptions for MCP tool arguments.
 * Avoids duplication across multiple @ToolArg annotations.
 */
public final class ToolArgDescriptions {

    private ToolArgDescriptions() {
    }

    // Workspace location arguments
    public static final String CWD =
        "Current working directory (project root path). " +
        "Example: '/home/user/project' or 'C:\\Users\\project'";

    // File URI arguments
    public static final String FILE_URI =
        "File URI (must be file:// URI as in LSP). " +
        "Example: 'file:///home/user/project/src/main/java/Main.java'";

    // Position arguments
    public static final String POSITION_LINE = "Line number (0-based)";
    public static final String POSITION_CHARACTER = "Character position in the line (0-based)";

    public static final String CANCELLATION = "Cancellation operation";
}
