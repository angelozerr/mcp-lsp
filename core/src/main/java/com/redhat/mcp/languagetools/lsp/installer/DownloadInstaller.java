package com.redhat.mcp.languagetools.lsp.installer;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads and extracts language servers from URLs.
 */
@ApplicationScoped
public class DownloadInstaller implements LspServerInstaller {

    private static final Logger LOG = Logger.getLogger(DownloadInstaller.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Override
    public boolean isInstalled(LspServerConfig config) {
        if (config.getInstaller() == null) {
            return false;
        }

        Path installDir = getInstallDir(config);
        return Files.exists(installDir) && Files.isDirectory(installDir);
    }

    @Override
    public CompletableFuture<Path> install(LspServerConfig config) {
        InstallerConfig installer = config.getInstaller();
        if (installer == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No installer configured for: " + config.getId())
            );
        }

        if (!installer.getType().equals("download")) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Installer type not supported: " + installer.getType())
            );
        }

        return downloadAndExtract(config, installer);
    }

    @Override
    public Path getInstallDir(LspServerConfig config) {
        if (config.getInstaller() == null) {
            return null;
        }
        return Paths.get(config.getInstaller().getResolvedInstallDir());
    }

    private CompletableFuture<Path> downloadAndExtract(LspServerConfig config, InstallerConfig installer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path installDir = Paths.get(installer.getResolvedInstallDir());
                String url = installer.getResolvedUrl();

                LOG.infof("Downloading %s version %s from: %s", config.getName(), installer.getVersion(), url);

                // Create temp directory
                Path tempDir = Files.createTempDirectory("mcp-lsp-download-" + config.getId());
                Path archiveFile = tempDir.resolve("archive." + installer.getFormat());

                // Download
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new IOException("Download failed with status: " + response.statusCode());
                }

                // Save to temp file
                try (InputStream in = new BufferedInputStream(response.body());
                     FileOutputStream out = new FileOutputStream(archiveFile.toFile())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    LOG.infof("Downloaded %d bytes", totalBytes);
                }

                LOG.infof("Extracting to: %s", installDir);

                // Create installation directory
                Files.createDirectories(installDir);

                // Extract based on format
                extract(archiveFile, installDir, installer.getFormat());

                // Cleanup
                Files.deleteIfExists(archiveFile);
                Files.deleteIfExists(tempDir);

                LOG.infof("%s version %s installed successfully at: %s", config.getName(), installer.getVersion(), installDir);
                return installDir;

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to download " + config.getId(), e);
            }
        });
    }

    private void extract(Path archiveFile, Path destDir, String format) throws IOException, InterruptedException {
        // Special case: JAR files don't need extraction, just copy them
        if (format.equals("jar")) {
            Path targetJar = destDir.resolve(archiveFile.getFileName());
            Files.copy(archiveFile, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.infof("Copied JAR to: %s", targetJar);
            return;
        }

        ProcessBuilder pb;

        if (format.equals("tar.gz") || format.equals("tgz")) {
            pb = new ProcessBuilder("tar", "-xzf", archiveFile.toString(), "-C", destDir.toString());
        } else if (format.equals("zip")) {
            pb = new ProcessBuilder("unzip", "-q", archiveFile.toString(), "-d", destDir.toString());
        } else {
            throw new UnsupportedOperationException("Unsupported archive format: " + format);
        }

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("Extraction failed with exit code: " + exitCode);
        }
    }
}
