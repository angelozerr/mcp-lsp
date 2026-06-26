package com.redhat.mcp.languagetools.lsp.installer.task;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Download and extract an archive.
 * Example:
 * {
 *   "download": {
 *     "name": "Download JDT.LS",
 *     "url": "https://...",
 *     "output": {
 *       "dir": "$USER_HOME$/.mcp-languagetools/lsp/jdtls",
 *       "file": {
 *         "name": "bin/jdtls",
 *         "executable": true
 *       }
 *     }
 *   }
 * }
 */
public class DownloadTask extends InstallerTask {

    private final String url;
    private final String outputDir;
    private final String outputFileName;
    private final boolean executable;

    public DownloadTask(String id, String name, String url, String outputDir, String outputFileName, boolean executable,
                        InstallerTask onFail, InstallerTask onSuccess) {
        super(id, name, onFail, onSuccess);
        this.url = url;
        this.outputDir = outputDir;
        this.outputFileName = outputFileName;
        this.executable = executable;
    }

    @Override
    protected boolean run(InstallerContext context) {
        try {
            String resolvedUrl = context.resolveVariables(url);
            String resolvedDir = context.resolveVariables(outputDir);

            context.log("  Downloading from: " + resolvedUrl);

            // Create temp directory for download
            Path tempDir = Files.createTempDirectory("mcp-lsp-download");
            Path downloadedFile = tempDir.resolve("archive");

            // Download
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                context.logError("  Download failed with status: " + response.statusCode());
                return false;
            }

            // Save to temp file
            try (InputStream in = new BufferedInputStream(response.body());
                 FileOutputStream out = new FileOutputStream(downloadedFile.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            context.log("  Downloaded to: " + downloadedFile);

            // Extract to output directory
            Path outputPath = Paths.get(resolvedDir);
            Files.createDirectories(outputPath);

            // Detect format and extract
            String fileName = downloadedFile.getFileName().toString();
            if (resolvedUrl.endsWith(".tar.gz") || resolvedUrl.endsWith(".tgz")) {
                extractTarGz(downloadedFile, outputPath, context);
            } else if (resolvedUrl.endsWith(".zip")) {
                extractZip(downloadedFile, outputPath, context);
            } else {
                // Single file, just copy
                Path targetFile = outputPath.resolve(outputFileName != null ? outputFileName : "file");
                Files.copy(downloadedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                context.log("  Copied to: " + targetFile);
            }

            // Set executable if needed
            if (executable && outputFileName != null) {
                Path executableFile = outputPath.resolve(context.resolveVariables(outputFileName));
                if (Files.exists(executableFile)) {
                    makeExecutable(executableFile);
                    context.log("  Made executable: " + executableFile);
                }
            }

            // Store output directory in context
            context.setProperty("output.dir", resolvedDir);
            if (outputFileName != null) {
                context.setProperty("output.file.name", context.resolveVariables(outputFileName));
            }

            // Clean up temp directory
            deleteRecursively(tempDir);

            context.log("  ✓ Installation complete: " + outputPath);
            return true;

        } catch (Exception e) {
            context.logError("  ✗ Download failed", e);
            return false;
        }
    }

    private void extractTarGz(Path archive, Path targetDir, InstallerContext context) throws IOException {
        context.log("  Extracting tar.gz to: " + targetDir);

        try (InputStream fileIn = Files.newInputStream(archive);
             GzipCompressorInputStream gzIn = new GzipCompressorInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (!tarIn.canReadEntryData(entry)) {
                    continue;
                }

                Path targetPath = targetDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    try (OutputStream out = Files.newOutputStream(targetPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = tarIn.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }

                    // Preserve permissions
                    if (entry.getMode() != 0 && !System.getProperty("os.name").toLowerCase().contains("win")) {
                        if ((entry.getMode() & 0111) != 0) {
                            makeExecutable(targetPath);
                        }
                    }
                }
            }
        }
    }

    private void extractZip(Path archive, Path targetDir, InstallerContext context) throws IOException {
        context.log("  Extracting zip to: " + targetDir);

        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    try (OutputStream out = Files.newOutputStream(targetPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zipIn.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

    private void makeExecutable(Path file) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursively(child);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
