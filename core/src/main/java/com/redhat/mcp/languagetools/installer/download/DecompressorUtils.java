package com.redhat.mcp.languagetools.installer.download;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;
import org.tukaani.xz.XZInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility class for decompressing archives.
 * Supports: .zip, .vsix, .tar, .tar.gz, .tgz, .gz, .tar.xz, .txz
 * Inspired by lsp4ij's DownloadUtils.
 */
public class DecompressorUtils {
    private static final Logger LOG = Logger.getLogger(DecompressorUtils.class);

    @FunctionalInterface
    public interface Decompressor {
        /**
         * Decompresses the specified file into the given directory.
         *
         * @param filePath the path to the compressed file.
         * @param targetDir the directory where the contents will be extracted.
         * @return the root directory of the extracted content, or null if there is no single root.
         * @throws IOException if decompression fails.
         */
        Path decompress(Path filePath, Path targetDir) throws IOException;
    }

    /**
     * Determines the appropriate decompressor based on the file's extension.
     *
     * @param filePath the path to the compressed file.
     * @return a Decompressor for the file type, or null if the extension is unsupported.
     */
    public static Decompressor getDecompressor(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".zip") || fileName.endsWith(".vsix")) {
            return DecompressorUtils::decompressZip;
        } else if (fileName.endsWith(".tar")) {
            return DecompressorUtils::decompressTar;
        } else if (fileName.endsWith("tar.gz") || fileName.endsWith(".tgz")) {
            return DecompressorUtils::decompressTgz;
        } else if (fileName.endsWith(".gz")) {
            return DecompressorUtils::decompressGz;
        } else if (fileName.endsWith("tar.xz") || fileName.endsWith(".txz")) {
            return DecompressorUtils::decompressTxz;
        }

        return null;
    }

    /**
     * Decompresses a ZIP archive (.zip or .vsix).
     */
    private static Path decompressZip(Path filePath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Set<String> topLevel = new HashSet<>();

        try (InputStream fis = Files.newInputStream(filePath);
             ZipInputStream zipInputStream = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName().replace("\\", "/");

                // Track top-level entries
                String[] parts = entryName.split("/");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    topLevel.add(parts[0]);
                }

                Path entryPath = targetDir.resolve(entryName);

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        zipInputStream.transferTo(out);
                    }
                }
                zipInputStream.closeEntry();
            }
        }

        return topLevel.size() == 1 ? targetDir.resolve(topLevel.iterator().next()) : null;
    }

    /**
     * Decompresses a TAR archive (.tar).
     */
    private static Path decompressTar(Path filePath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Set<String> topLevel = new HashSet<>();

        try (InputStream fis = Files.newInputStream(filePath);
             TarArchiveInputStream tarInputStream = new TarArchiveInputStream(fis)) {

            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                String entryName = entry.getName().replace("\\", "/");

                // Track top-level entries
                String[] parts = entryName.split("/");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    topLevel.add(parts[0]);
                }

                Path entryPath = targetDir.resolve(entryName);

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

        return topLevel.size() == 1 ? targetDir.resolve(topLevel.iterator().next()) : null;
    }

    /**
     * Decompresses a GZIP-compressed TAR archive (.tar.gz or .tgz).
     */
    private static Path decompressTgz(Path filePath, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzipInputStream = new GZIPInputStream(bis)) {

            Path tarFilePath = Files.createTempFile("temp", ".tar");
            try {
                Files.copy(gzipInputStream, tarFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path result = decompressTar(tarFilePath, targetDir);
                return result;
            } finally {
                Files.deleteIfExists(tarFilePath);
            }
        }
    }

    /**
     * Decompresses a GZIP file (not a TAR archive).
     */
    private static Path decompressGz(Path filePath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzipInputStream = new GZIPInputStream(bis)) {

            Path outputFile = targetDir.resolve(stripExtension(filePath));
            Files.copy(gzipInputStream, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return outputFile;
        }
    }

    /**
     * Decompresses a XZ-compressed TAR archive (.tar.xz or .txz).
     */
    private static Path decompressTxz(Path filePath, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             XZInputStream xzInputStream = new XZInputStream(bis)) {

            Path tarFilePath = Files.createTempFile("temp", ".tar");
            try {
                Files.copy(xzInputStream, tarFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path result = decompressTar(tarFilePath, targetDir);
                return result;
            } finally {
                Files.deleteIfExists(tarFilePath);
            }
        }
    }

    /**
     * Removes the extension from a filename.
     */
    private static String stripExtension(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        return (index > 0) ? fileName.substring(0, index) : fileName;
    }
}
