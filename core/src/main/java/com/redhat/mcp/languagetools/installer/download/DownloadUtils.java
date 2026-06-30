package com.redhat.mcp.languagetools.installer.download;

import com.redhat.mcp.languagetools.installer.ProgressIndicator;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for downloading files with progress tracking.
 * Inspired by lsp4ij's DownloadUtils.
 */
public class DownloadUtils {
    private static final Logger LOG = Logger.getLogger(DownloadUtils.class);

    /**
     * Result of a download operation.
     */
    public static class DownloadResult {
        public final long contentLength;

        public DownloadResult(long contentLength) {
            this.contentLength = contentLength;
        }
    }

    /**
     * Downloads a file from the specified URL to the given local path.
     *
     * @param downloadUrl the URL to download from (must be a valid HTTP or HTTPS URL).
     * @param downloadedFile the target path where the file will be saved.
     * @param progressIndicator an optional progress indicator to display download progress.
     * @return download result with content length
     * @throws IOException if the download fails or the file cannot be written.
     */
    public static DownloadResult download(String downloadUrl,
                                          Path downloadedFile,
                                          ProgressIndicator progressIndicator) throws IOException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // Handle redirects (e.g., Eclipse downloads return 302)
            if (response.statusCode() == 301 || response.statusCode() == 302 ||
                response.statusCode() == 307 || response.statusCode() == 308) {
                String redirectUrl = response.headers().firstValue("Location").orElse(null);
                if (redirectUrl != null) {
                    LOG.infof("Following redirect to: %s", redirectUrl);
                    HttpRequest redirectRequest = HttpRequest.newBuilder()
                            .uri(URI.create(redirectUrl))
                            .build();
                    response = client.send(redirectRequest, HttpResponse.BodyHandlers.ofInputStream());
                }
            }

            if (response.statusCode() != 200) {
                throw new IOException("Download failed with status " + response.statusCode() + ": " + downloadUrl);
            }

            // Get content length for progress tracking
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            // Notify progress indicator about content length before starting
            if (progressIndicator != null && progressIndicator instanceof ContentLengthAware) {
                ((ContentLengthAware) progressIndicator).setContentLength(contentLength);
            }

            // Ensure parent directory exists
            Files.createDirectories(downloadedFile.getParent());

            // Download with progress tracking
            try (InputStream inputStream = response.body();
                 OutputStream fileOut = Files.newOutputStream(downloadedFile)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (progressIndicator != null) {
                        progressIndicator.checkCanceled();
                    }
                    fileOut.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    // Update progress
                    if (progressIndicator != null && contentLength > 0) {
                        double fraction = (double) downloaded / contentLength;
                        progressIndicator.setFraction(fraction);
                    }
                }
            }

            return new DownloadResult(contentLength);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }
}
