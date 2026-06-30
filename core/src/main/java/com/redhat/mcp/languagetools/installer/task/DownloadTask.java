package com.redhat.mcp.languagetools.installer.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.mcp.languagetools.installer.InstallerContext;
import com.redhat.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Task that downloads and extracts a file.
 */
public class DownloadTask implements InstallerTask {
    private static final Logger LOG = Logger.getLogger(DownloadTask.class);

    private final String name;
    private final String url;
    private final String outputDir;
    private final InstallerTask onSuccessTask;

    public DownloadTask(String name, String url, String outputDir, InstallerTask onSuccessTask) {
        this.name = name;
        this.url = url;
        this.outputDir = outputDir;
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

            // Download file
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl))
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // Handle redirects (e.g., Eclipse downloads return 302)
            if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307 || response.statusCode() == 308) {
                String redirectUrl = response.headers().firstValue("Location").orElse(null);
                if (redirectUrl != null) {
                    if (trace != null) {
                        trace.info("Following redirect to: " + redirectUrl);
                    }
                    HttpRequest redirectRequest = HttpRequest.newBuilder()
                            .uri(URI.create(redirectUrl))
                            .build();
                    response = client.send(redirectRequest, HttpResponse.BodyHandlers.ofInputStream());
                }
            }

            if (response.statusCode() != 200) {
                LOG.errorf("Download failed with status %d: %s", response.statusCode(), resolvedUrl);
                if (trace != null) {
                    trace.error("Download failed with status " + response.statusCode());
                }
                return false;
            }

            context.getProgress().setText("Extracting " + name);
            context.getProgress().setFraction(0.5);

            // Extract tar.gz
            try (InputStream inputStream = response.body();
                 GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream);
                 TarArchiveInputStream tarInputStream = new TarArchiveInputStream(gzipInputStream)) {

                TarArchiveEntry entry;
                while ((entry = tarInputStream.getNextTarEntry()) != null) {
                    context.checkCanceled();

                    Path entryPath = outputPath.resolve(entry.getName());

                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        try (OutputStream out = Files.newOutputStream(entryPath)) {
                            tarInputStream.transferTo(out);
                        }

                        // Preserve executable permissions
                        if ((entry.getMode() & 0100) != 0) {
                            entryPath.toFile().setExecutable(true);
                        }
                    }
                }
            }

            context.getProgress().setFraction(1.0);

            // Store output dir in context for onSuccess tasks
            context.setVariable("output.dir", resolvedOutputDir);

            if (trace != null) {
                trace.info("Downloaded and extracted to: " + resolvedOutputDir);
            }

            // Execute onSuccess task
            if (onSuccessTask != null) {
                return onSuccessTask.execute(context);
            }

            return true;

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
            String outputDir = config.get("output").get("dir").asText();

            // Parse onSuccess tasks
            InstallerTask onSuccessTask = null;
            if (config.has("onSuccess")) {
                JsonNode onSuccess = config.get("onSuccess");
                onSuccessTask = parseTaskNode(onSuccess);
            }

            return new DownloadTask(name, url, outputDir, onSuccessTask);
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
}
