package com.redhat.mcp.languagetools.lsp;

import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension contributions (Eclipse plugin.xml-like system).
 * Allows servers to extend other servers with custom contributions.
 *
 * Structure:
 * {
 *   "contributes": {
 *     "jdtls": {
 *       "bundles": ["plugins/*.jar"],
 *       "bindRequest": ["microprofile/java/projectInfo", ...]
 *     },
 *     "microprofile": {
 *       "bundles": ["lib/*.jar"]
 *     }
 *   }
 * }
 *
 * Each server interprets its own contribution format by looking for contributes.{serverId}.
 */
public class Contributes {

    /**
     * Server-specific contributions.
     * Key = target server ID (e.g., "jdtls", "microprofile")
     * Value = contribution data (interpreted by that target server)
     *
     * Example:
     * "jdtls": {
     *   "bundles": ["plugins/bundle.jar"],
     *   "bindRequest": ["microprofile/java/projectInfo"]
     * }
     */
    private Map<String, JsonElement> contributions = new HashMap<>();

    public Map<String, JsonElement> getContributions() {
        return contributions;
    }

    public void setContributions(Map<String, JsonElement> contributions) {
        this.contributions = contributions;
    }

    /**
     * Get contribution for a specific server.
     */
    public JsonElement getContribution(String serverId) {
        return contributions != null ? contributions.get(serverId) : null;
    }

    /**
     * Check if there are contributions for a specific server.
     */
    public boolean hasContribution(String serverId) {
        return contributions != null && contributions.containsKey(serverId);
    }
}
