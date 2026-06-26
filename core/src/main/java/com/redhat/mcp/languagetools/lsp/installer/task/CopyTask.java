package com.redhat.mcp.languagetools.lsp.installer.task;

import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Copy a file or directory.
 * Example:
 * {
 *   "copy": {
 *     "name": "Copy configuration",
 *     "source": "${output.dir}/config.template",
 *     "destination": "${output.dir}/config.json"
 *   }
 * }
 */
public class CopyTask extends InstallerTask {

    private final String source;
    private final String destination;
    private final boolean overwrite;

    public CopyTask(String id, String name, String source, String destination, boolean overwrite,
                    InstallerTask onFail, InstallerTask onSuccess) {
        super(id, name, onFail, onSuccess);
        this.source = source;
        this.destination = destination;
        this.overwrite = overwrite;
    }

    @Override
    protected boolean run(InstallerContext context) {
        try {
            String resolvedSource = context.resolveVariables(source);
            String resolvedDestination = context.resolveVariables(destination);

            Path destPath = Paths.get(resolvedDestination);

            context.log("  Copying from: " + resolvedSource);
            context.log("  Copying to: " + destPath);

            // Check if source is a resource path (starts with /)
            if (resolvedSource.startsWith("/")) {
                // Copy from classpath resource
                return copyFromResource(resolvedSource, destPath, context);
            }

            // Otherwise, copy from filesystem
            Path sourcePath = Paths.get(resolvedSource);

            // Check if source exists
            if (!Files.exists(sourcePath)) {
                context.log("  ✗ Source file does not exist: " + sourcePath);
                return false;
            }

            // Create parent directory if needed
            Path parentDir = destPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            // Copy file
            if (overwrite) {
                Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (Files.exists(destPath)) {
                    context.log("  Destination already exists, skipping: " + destPath);
                    return true;
                }
                Files.copy(sourcePath, destPath);
            }

            context.log("  Copied successfully");

            // Store destination in context for use by subsequent tasks
            context.setProperty("output.file", destPath.toString());

            return true;

        } catch (IOException e) {
            context.log("  ✗ Failed to copy file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Copy a file from classpath resources.
     */
    private boolean copyFromResource(String resourcePath, Path destPath, InstallerContext context) throws IOException {
        // Load resource from classpath
        java.io.InputStream resourceStream = getClass().getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            context.log("  ✗ Resource not found: " + resourcePath);
            return false;
        }

        // Create parent directory if needed
        Path parentDir = destPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        // Copy resource to destination
        try (resourceStream) {
            Files.copy(resourceStream, destPath, StandardCopyOption.REPLACE_EXISTING);
        }

        context.log("  ✓ Copied from resource successfully");

        // Store destination in context for use by subsequent tasks
        context.setProperty("output.file", destPath.toString());
        context.setProperty("output.dir", destPath.getParent().toString());

        return true;
    }
}
