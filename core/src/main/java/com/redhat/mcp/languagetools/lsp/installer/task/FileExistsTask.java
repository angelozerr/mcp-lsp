package com.redhat.mcp.languagetools.lsp.installer.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;

/**
 * Check if a file exists.
 * Example:
 * {
 *   "fileExists": {
 *     "name": "Check if installed",
 *     "file": "$USER_HOME$/.mcp-languagetools/lsp/jdtls/bin/jdtls"
 *   }
 * }
 */
public class FileExistsTask extends InstallerTask {

    private final String file;

    public FileExistsTask(String id, String name, String file, InstallerTask onFail, InstallerTask onSuccess) {
        super(id, name, onFail, onSuccess);
        this.file = file;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedFile = context.resolveVariables(file);
        Path filePath = Paths.get(resolvedFile);

        boolean exists = Files.exists(filePath);

        // Show original template and resolved path
        String logMessage = file.equals(resolvedFile)
            ? file  // No variables to resolve
            : file + " (" + resolvedFile + ")";  // Show both template and resolved path

        context.log(exists ? "  ✓ File exists: " + logMessage : "  ✗ File not found: " + logMessage);

        return exists;
    }
}
