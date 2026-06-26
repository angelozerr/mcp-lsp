package com.redhat.mcp.languagetools.workspace;

import java.net.URI;

/**
 * CDI event fired when workspaces change (created or closed).
 */
public record WorkspaceChangeEvent(
    Type type,
    URI workspaceUri
) {
    public enum Type {
        CREATED,
        CLOSED
    }
}
