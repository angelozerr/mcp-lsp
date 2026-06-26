package com.redhat.mcp.languagetools.lsp.installer.task;

import com.google.gson.JsonObject;

/**
 * Factory for FileExistsTask.
 * Example JSON:
 * {
 *   "fileExists": {
 *     "name": "Check if installed",
 *     "file": "$USER_HOME$/.mcp-languagetools/lsp/jdtls/bin/jdtls"
 *   }
 * }
 */
public class FileExistsTaskFactory extends InstallerTaskFactoryBase {

    private static final String FILE_JSON_PROPERTY = "file";

    public FileExistsTaskFactory(InstallerTaskRegistry registry) {
        super(registry);
    }

    @Override
    protected InstallerTask create(String id, String name, InstallerTask onFail,
                                    InstallerTask onSuccess, JsonObject json) {
        String file = getString(json, FILE_JSON_PROPERTY);
        if (file == null) {
            throw new IllegalArgumentException("Missing 'file' property in fileExists task");
        }

        return new FileExistsTask(id, name, file, onFail, onSuccess);
    }
}
