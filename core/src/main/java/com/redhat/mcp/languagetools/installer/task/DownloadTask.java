package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.mcp.languagetools.installer.InstallerContext;
import com.redhat.mcp.languagetools.installer.ProgressIndicator;
import com.redhat.mcp.languagetools.installer.download.DecompressorUtils;
import com.redhat.mcp.languagetools.installer.download.DownloadUtils;
import com.redhat.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Task that downloads and extracts a file.
 */
public class DownloadTask implements InstallerTask {
    private static final Logger LOG = Logger.getLogger(DownloadTask.class);

    private final String name;
    private final String url;
    private final String outputDir;
    private final String outputFileName;  // OS-specific output file name
    private final InstallerTask onSuccessTask;

    public DownloadTask(String name, String url, String outputDir, String outputFileName, InstallerTask onSuccessTask) {
        this.name = name;
        this.url = url;
        this.outputDir = outputDir;
        this.outputFileName = outputFileName;
        this.onSuccessTask = onSuccessTask;
    }

    @Override
    public boolean execute(InstallerContext context) {
        context.checkCanceled();

        String resolvedUrl = context.resolveVariables(url);
        String resolvedOutputDir = context.resolveVariables(outputDir);

        TraceCollector trace = context.getConfig().getTraceCollector();
        if (trace != null) {
            trace.info("Downloading from: " + resolvedUrl);
        }

        context.getProgress().setText("Downloading " + name);
        context.getProgress().setFraction(0.0);

        try {
            Path outputPath = Paths.get(resolvedOutputDir);
            Files.createDirectories(outputPath);

            // Determine file extension from URL
            String fileName = resolvedUrl.substring(resolvedUrl.lastIndexOf('/') + 1);
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            Path downloadedFile = Files.createTempFile("download-", fileName);

            try {
                // Download file with progress tracking
                // Note: Don't enable sendProgressUpdates on TraceProgressIndicator because
                // ProgressIndicatorWrapper already sends its own UPDATE messages with MB/MB display
                ProgressIndicatorWrapper downloadProgress = new ProgressIndicatorWrapper(context, trace, name);

                // Download (contentLength will be set automatically via ContentLengthAware interface)
                DownloadUtils.DownloadResult result = DownloadUtils.download(resolvedUrl, downloadedFile, downloadProgress);

                context.getProgress().setText("Extracting " + name);
                context.getProgress().setFraction(0.7);

                if (trace != null) {
                    trace.info("Extracting to: " + resolvedOutputDir);
                }

                // Decompress based on file extension
                DecompressorUtils.Decompressor decompressor = DecompressorUtils.getDecompressor(downloadedFile);
                if (decompressor == null) {
                    LOG.errorf("Unsupported archive format: %s", fileName);
                    if (trace != null) {
                        trace.error("Unsupported archive format: " + fileName);
                    }
                    return false;
                }

                Path rootDir = decompressor.decompress(downloadedFile, outputPath);

                context.getProgress().setFraction(1.0);

                // Store output dir and file name in context for onSuccess tasks
                context.setVariable("output.dir", resolvedOutputDir);
                if (outputFileName != null) {
                    String resolvedFileName = context.resolveVariables(outputFileName);
                    context.setVariable("output.file.name", resolvedFileName);
                }

                if (trace != null) {
                    trace.info("Downloaded and extracted to: " + resolvedOutputDir);
                }

                // Execute onSuccess task
                if (onSuccessTask != null) {
                    return onSuccessTask.execute(context);
                }

                return true;

            } finally {
                // Clean up temp file
                Files.deleteIfExists(downloadedFile);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Download failed: %s", resolvedUrl);
            if (trace != null) {
                trace.error("Download failed: " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Factory for DownloadTask.
     */
    public static class Factory implements InstallerTaskFactory {
        // Lazy singleton registry to avoid circular initialization
        private static volatile InstallerTaskRegistry registry;

        public Factory() {
            // Don't create registry in constructor - would cause infinite recursion
        }

        private static InstallerTaskRegistry getRegistry() {
            if (registry == null) {
                synchronized (Factory.class) {
                    if (registry == null) {
                        registry = new InstallerTaskRegistry();
                    }
                }
            }
            return registry;
        }

        @Override
        public String getType() {
            return "download";
        }

        @Override
        public InstallerTask createTask(JsonNode config) {
            String name = config.has("name") ? config.get("name").asText() : "Download";
            String url = config.get("url").asText();
            JsonNode output = config.get("output");
            String outputDir = output.get("dir").asText();

            // Parse output.file.name (can be a simple string or OS-specific map)
            String outputFileName = null;
            if (output.has("file") && output.get("file").has("name")) {
                JsonNode fileNameNode = output.get("file").get("name");
                if (fileNameNode.isTextual()) {
                    // Simple string: "bin/jdtls"
                    outputFileName = fileNameNode.asText();
                } else if (fileNameNode.isObject()) {
                    // OS-specific map: {"windows": "bin/jdtls.bat", "default": "bin/jdtls"}
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win") && fileNameNode.has("windows")) {
                        outputFileName = fileNameNode.get("windows").asText();
                    } else if (os.contains("mac") && fileNameNode.has("mac")) {
                        outputFileName = fileNameNode.get("mac").asText();
                    } else if (os.contains("nix") || os.contains("nux") && fileNameNode.has("linux")) {
                        outputFileName = fileNameNode.get("linux").asText();
                    } else if (fileNameNode.has("default")) {
                        outputFileName = fileNameNode.get("default").asText();
                    }
                }
            }

            // Parse onSuccess tasks
            InstallerTask onSuccessTask = null;
            if (config.has("onSuccess")) {
                JsonNode onSuccess = config.get("onSuccess");
                onSuccessTask = parseTaskNode(onSuccess);
            }

            return new DownloadTask(name, url, outputDir, outputFileName, onSuccessTask);
        }

        private InstallerTask parseTaskNode(JsonNode taskNode) {
            // Find the task type (first key in the object)
            var fieldNames = taskNode.fieldNames();
            if (!fieldNames.hasNext()) {
                return null;
            }

            String taskType = fieldNames.next();
            JsonNode taskConfig = taskNode.get(taskType);

            return getRegistry().createTask(taskType, taskConfig);
        }
    }

    /**
     * Wrapper for ProgressIndicator that tracks download progress with TraceCollector.
     */
    private static class ProgressIndicatorWrapper implements ProgressIndicator, com.redhat.mcp.languagetools.installer.download.ContentLengthAware {
        private final InstallerContext context;
        private final TraceCollector trace;
        private final String name;
        private long contentLength = -1;

        public ProgressIndicatorWrapper(InstallerContext context, TraceCollector trace, String name) {
            this.context = context;
            this.trace = trace;
            this.name = name;
        }

        public void setContentLength(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public void setText(String text) {
            context.getProgress().setText(text);
        }

        @Override
        public void setText2(String text) {
            context.getProgress().setText2(text);
        }

        @Override
        public void setFraction(double fraction) {
            // Scale to 70% (download takes 70%, extract takes 30%)
            context.getProgress().setFraction(fraction * 0.7);

            // Update trace with MB/MB and %
            if (trace != null && contentLength > 0) {
                long downloaded = (long) (fraction * contentLength);
                String downloadedMB = String.format("%.1f", downloaded / 1024.0 / 1024.0);
                String totalMB = String.format("%.1f", contentLength / 1024.0 / 1024.0);
                trace.update(String.format("Downloading %s: %s MB / %s MB (%.0f%%)",
                        name, downloadedMB, totalMB, fraction * 100));
            }
        }

        @Override
        public boolean isCanceled() {
            return context.getProgress().isCanceled();
        }

        @Override
        public void checkCanceled() {
            context.checkCanceled();
        }
    }
}
