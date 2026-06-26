package com.redhat.mcp.languagetools.admin.ws;

import com.redhat.mcp.languagetools.admin.dto.WorkspaceDTO;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * WebSocket message for full workspace list updates.
 */
@RegisterForReflection
public record WorkspacesUpdateWsMessage(
    String type,  // "workspaces-update"
    List<WorkspaceDTO> workspaces
) {
}
