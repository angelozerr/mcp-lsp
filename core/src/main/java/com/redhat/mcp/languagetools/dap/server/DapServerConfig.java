package com.redhat.mcp.languagetools.dap.server;

import com.redhat.mcp.languagetools.server.ServerConfigBase;

import java.util.HashMap;
import java.util.Map;

/**
 * DAP (Debug Adapter Protocol) server configuration loaded from server.json.
 * Similar to LspServerConfig but with DAP-specific fields.
 */
public class DapServerConfig extends ServerConfigBase {

    private Map<String, String> launch;  // OS-specific launch commands
    private Map<String, Object> attach;  // Attach configuration
    private String debugServerReadyPattern;
    private Map<String, Object> env = new HashMap<>();
    private String workingDirectory;

    // Getters and setters (id, name, description, installer, documentSelector, trace inherited from ServerConfigBase)

    public Map<String, String> getLaunch() {
        return launch;
    }

    public void setLaunch(Map<String, String> launch) {
        this.launch = launch;
    }

    /**
     * Get the launch command for the current OS.
     */
    public String getLaunchForCurrentOS() {
        if (launch == null) {
            return null;
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win") && launch.containsKey("windows")) {
            return launch.get("windows");
        } else if (os.contains("mac") && launch.containsKey("mac")) {
            return launch.get("mac");
        } else if (launch.containsKey("default")) {
            return launch.get("default");
        }
        return null;
    }

    public Map<String, Object> getAttach() {
        return attach;
    }

    public void setAttach(Map<String, Object> attach) {
        this.attach = attach;
    }

    public String getDebugServerReadyPattern() {
        return debugServerReadyPattern;
    }

    public void setDebugServerReadyPattern(String debugServerReadyPattern) {
        this.debugServerReadyPattern = debugServerReadyPattern;
    }

    public Map<String, Object> getEnv() {
        return env;
    }

    public void setEnv(Map<String, Object> env) {
        this.env = env;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }
}
