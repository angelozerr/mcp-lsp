package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;

/**
 * Factory for DownloadTask.
 * Example JSON:
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
public class DownloadTaskFactory extends InstallerTaskFactoryBase {

    private static final String URL_JSON_PROPERTY = "url";
    private static final String OUTPUT_JSON_PROPERTY = "output";
    private static final String OUTPUT_DIR_JSON_PROPERTY = "dir";
    private static final String OUTPUT_FILE_JSON_PROPERTY = "file";
    private static final String OUTPUT_FILE_NAME_JSON_PROPERTY = "name";
    private static final String OUTPUT_FILE_EXECUTABLE_JSON_PROPERTY = "executable";

    public DownloadTaskFactory(InstallerTaskRegistry registry) {
        super(registry);
    }

    @Override
    protected InstallerTask create(String id, String name, InstallerTask onFail,
                                    InstallerTask onSuccess, JsonObject json) {
        // Get URL (supports OS-specific URLs)
        String url = getStringFromOs(json, URL_JSON_PROPERTY);
        if (url == null) {
            throw new IllegalArgumentException("Missing 'url' property in download task");
        }

        // Get output configuration
        JsonObject outputObj = getJsonObject(json, OUTPUT_JSON_PROPERTY);
        if (outputObj == null) {
            throw new IllegalArgumentException("Missing 'output' property in download task");
        }

        String outputDir = getStringFromOs(outputObj, OUTPUT_DIR_JSON_PROPERTY);
        if (outputDir == null) {
            throw new IllegalArgumentException("Missing 'output.dir' property in download task");
        }

        // Optional file configuration
        String outputFileName = null;
        boolean executable = false;

        JsonObject fileObj = getJsonObject(outputObj, OUTPUT_FILE_JSON_PROPERTY);
        if (fileObj != null) {
            outputFileName = getStringFromOs(fileObj, OUTPUT_FILE_NAME_JSON_PROPERTY);
            executable = getBoolean(fileObj, OUTPUT_FILE_EXECUTABLE_JSON_PROPERTY);
        }

        return new DownloadTask(id, name, url, outputDir, outputFileName, executable, onFail, onSuccess);
    }
}
