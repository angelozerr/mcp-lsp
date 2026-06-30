package com.redhat.mcp.languagetools.installer;

import com.redhat.mcp.languagetools.server.ServerConfigBase;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context for installation tasks.
 * Contains progress indicator, variables, and configuration.
 */
public class InstallerContext {
    private final ProgressIndicator progress;
    private final Map<String, String> variables;
    private final Path installDir;
    private final ServerConfigBase config;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}|\\$([A-Z_]+)\\$");

    public InstallerContext(ServerConfigBase config, Path installDir, ProgressIndicator progress) {
        this.config = config;
        this.installDir = installDir;
        this.progress = progress;
        this.variables = new HashMap<>();

        // Initialize standard variables
        variables.put("SERVER_HOME", installDir.toString());
        variables.put("SERVER_ID", config.getId());
        variables.put("SERVER_NAME", config.getName());
    }

    public ProgressIndicator getProgress() {
        return progress;
    }

    public Path getInstallDir() {
        return installDir;
    }

    public ServerConfigBase getConfig() {
        return config;
    }

    /**
     * Sets a variable that can be used in templates.
     */
    public void setVariable(String key, String value) {
        variables.put(key, value);
    }

    /**
     * Gets a variable value.
     */
    public String getVariable(String key) {
        return variables.get(key);
    }

    /**
     * Resolves variables in a template string.
     * Supports: ${variable} and $VARIABLE$
     */
    public String resolveVariables(String template) {
        if (template == null) {
            return null;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String value = variables.get(varName);

            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            } else {
                // Keep original if variable not found
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Checks if installation was cancelled.
     */
    public boolean isCanceled() {
        return progress.isCanceled();
    }

    /**
     * Throws exception if cancelled.
     */
    public void checkCanceled() {
        progress.checkCanceled();
    }
}
