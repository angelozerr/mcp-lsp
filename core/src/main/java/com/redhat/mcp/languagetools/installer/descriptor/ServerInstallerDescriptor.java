package com.redhat.mcp.languagetools.installer.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Descriptor for installer.json file.
 */
@RegisterForReflection
public class ServerInstallerDescriptor {
    private String id;
    private String name;
    private JsonNode check;  // Check task (optional)
    private JsonNode run;    // Run task (required)

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getCheck() {
        return check;
    }

    public void setCheck(JsonNode check) {
        this.check = check;
    }

    public JsonNode getRun() {
        return run;
    }

    public void setRun(JsonNode run) {
        this.run = run;
    }
}
