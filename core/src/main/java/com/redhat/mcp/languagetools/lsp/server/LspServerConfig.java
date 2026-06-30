package com.redhat.mcp.languagetools.lsp.server;

import com.redhat.mcp.languagetools.server.ServerConfigBase;
import com.redhat.mcp.languagetools.lsp.Contributes;
import com.redhat.mcp.languagetools.lsp.DocumentSelector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a language server.
 * Can be loaded from JSON or built programmatically.
 */
public class LspServerConfig extends ServerConfigBase {

    /**
     * Command to execute the language server (simple string or OS-specific map)
     * Can be either:
     * - A simple command string (used for all OS)
     * - A map with "windows", "linux", "mac", "default" keys for OS-specific commands
     */
    private Object command;

    /**
     * Command line arguments (optional, used only with simple string command)
     */
    private List<String> args = new ArrayList<>();

    /**
     * Environment variables
     */
    private Map<String, String> env = new HashMap<>();

    /**
     * Working directory for the server process
     */
    private String workingDirectory;

    /**
     * Server initialization options
     */
    private Map<String, Object> initializationOptions = new HashMap<>();

    /**
     * True if this is a pure extension (server-extension.json), false if it's a server (server.json).
     * Extensions contribute to other servers but don't run a separate process.
     */
    private boolean isExtension;

    /**
     * Contributions (VS Code-like extension system)
     */
    private Contributes contributes;

    public LspServerConfig() {
    }

    public LspServerConfig(String id) {
        this.id = id;
    }

    // Builder pattern
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final LspServerConfig config;

        public Builder(String id) {
            this.config = new LspServerConfig(id);
        }

        public Builder command(String command) {
            config.command = command;
            return this;
        }

        public Builder args(List<String> args) {
            config.args = new ArrayList<>(args);
            return this;
        }

        public Builder addArg(String arg) {
            config.args.add(arg);
            return this;
        }

        public Builder documentSelector(List<DocumentSelector> selectors) {
            config.setDocumentSelector(new ArrayList<>(selectors));
            return this;
        }

        public Builder addDocumentSelector(DocumentSelector selector) {
            config.getDocumentSelector().add(selector);
            return this;
        }

        public Builder env(Map<String, String> env) {
            config.env = new HashMap<>(env);
            return this;
        }

        public Builder workingDirectory(String dir) {
            config.workingDirectory = dir;
            return this;
        }

        public Builder initializationOptions(Map<String, Object> options) {
            config.initializationOptions = new HashMap<>(options);
            return this;
        }

        public LspServerConfig build() {
            // Command is required unless this is a contribution-only config
            if (config.command == null && config.contributes == null) {
                throw new IllegalStateException("command or contributes is required");
            }
            // documentSelector is optional for contribution-only configs
            if (config.getDocumentSelector().isEmpty() && config.command != null) {
                throw new IllegalStateException("documentSelector is required for servers with command");
            }
            return config;
        }
    }

    /**
     * Check if this server can handle the given file.
     */
    public boolean canHandle(String uri, String language) {
        return getDocumentSelector().stream()
                .anyMatch(selector -> selector.matches(uri, language));
    }

    /**
     * Check if this is a contribution-only config (no command, only contributes to other servers).
     */
    public boolean isContributionOnly() {
        return command == null && contributes != null;
    }

    // Getters and setters (id, name, description, installer inherited from ServerConfigBase)

    public Object getCommand() {
        return command;
    }

    public void setCommand(Object command) {
        this.command = command;
    }

    /**
     * Get the command for the current OS.
     */
    @SuppressWarnings("unchecked")
    public String getCommandForCurrentOS() {
        if (command instanceof String) {
            return (String) command;
        }
        if (command instanceof Map) {
            Map<String, String> commandMap = (Map<String, String>) command;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                return commandMap.getOrDefault("windows", commandMap.get("default"));
            } else if (os.contains("mac")) {
                return commandMap.getOrDefault("mac", commandMap.get("default"));
            } else if (os.contains("linux")) {
                return commandMap.getOrDefault("linux", commandMap.get("default"));
            }
            return commandMap.get("default");
        }
        return null;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Map<String, Object> getInitializationOptions() {
        return initializationOptions;
    }

    public void setInitializationOptions(Map<String, Object> initializationOptions) {
        this.initializationOptions = initializationOptions;
    }

    public boolean isExtension() {
        return isExtension;
    }

    public void setExtension(boolean extension) {
        isExtension = extension;
    }

    public Contributes getContributes() {
        return contributes;
    }

    public void setContributes(Contributes contributes) {
        this.contributes = contributes;
    }

    @Override
    public String toString() {
        return "LspServerConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", command='" + command + '\'' +
                ", args=" + args +
                ", documentSelector=" + getDocumentSelector() +
                '}';
    }
}
