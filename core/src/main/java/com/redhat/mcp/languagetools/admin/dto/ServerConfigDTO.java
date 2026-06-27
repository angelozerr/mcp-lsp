package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.lsp.DocumentSelector;

import java.util.List;
import java.util.Map;

/**
 * Static server configuration - immutable server descriptor.
 * This represents the server's definition independent of any workspace or runtime state.
 */
public record ServerConfigDTO(
    String id,
    String name,
    String description,
    List<DocumentSelector> documentSelector,
    Object command,
    List<String> args,
    Map<String, String> env,
    String workingDirectory,
    Map<String, Object> initializationOptions,
    Map<String, Map<String, List<?>>> contributions
) {
}
