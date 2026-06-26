package com.redhat.mcp.languagetools.language;

import java.util.List;

/**
 * Language definition following VSCode contributes.languages structure.
 * See: https://code.visualstudio.com/api/references/contribution-points#contributes.languages
 */
public record LanguageDefinition(
    String id,
    List<String> extensions,
    List<String> aliases,
    List<String> filenames,
    List<String> filenamePatterns,
    String firstLine
) {
    public LanguageDefinition {
        // Default empty lists if null
        if (extensions == null) extensions = List.of();
        if (aliases == null) aliases = List.of();
        if (filenames == null) filenames = List.of();
        if (filenamePatterns == null) filenamePatterns = List.of();
    }
}
