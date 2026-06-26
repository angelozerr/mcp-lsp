package com.redhat.mcp.languagetools.lsp.installer;

/**
 * Configuration for a language server installer.
 */
public class InstallerConfig {

    /**
     * Type of installer (e.g., "download", "npm", "manual")
     */
    private String type;

    /**
     * Version to install
     */
    private String version;

    /**
     * Build timestamp (for JDT.LS style versioning)
     */
    private String timestamp;

    /**
     * Download URL (supports ${version}, ${timestamp} variables)
     */
    private String url;

    /**
     * Installation directory (supports ${user.home} variable)
     */
    private String installDir;

    /**
     * Archive format (e.g., "tar.gz", "zip")
     */
    private String format = "tar.gz";

    public InstallerConfig() {
    }

    // Getters and setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getInstallDir() {
        return installDir;
    }

    public void setInstallDir(String installDir) {
        this.installDir = installDir;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    /**
     * Resolve variables in URL and installDir.
     */
    public String getResolvedUrl() {
        if (url == null) {
            return null;
        }
        return url.replace("${version}", version != null ? version : "")
                .replace("${timestamp}", timestamp != null ? timestamp : "");
    }

    public String getResolvedInstallDir() {
        if (installDir == null) {
            return null;
        }
        return installDir.replace("${user.home}", System.getProperty("user.home"));
    }

    @Override
    public String toString() {
        return "InstallerConfig{" +
                "type='" + type + '\'' +
                ", version='" + version + '\'' +
                ", installDir='" + installDir + '\'' +
                '}';
    }
}
