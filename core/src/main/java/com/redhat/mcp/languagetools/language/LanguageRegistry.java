package com.redhat.mcp.languagetools.language;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registry of language definitions loaded from languages.json.
 * Matches files to language IDs using VSCode-style matching rules.
 */
@ApplicationScoped
public class LanguageRegistry {

    private static final Logger LOG = Logger.getLogger(LanguageRegistry.class);
    private final List<LanguageDefinition> languages;

    public LanguageRegistry() {
        this.languages = loadLanguages();
    }

    private List<LanguageDefinition> loadLanguages() {
        try (InputStream is = getClass().getResourceAsStream("/languages.json")) {
            if (is == null) {
                LOG.warn("languages.json not found, using empty language list");
                return List.of();
            }

            Gson gson = new Gson();
            List<LanguageDefinition> loaded = gson.fromJson(
                new InputStreamReader(is),
                new TypeToken<List<LanguageDefinition>>() {}.getType()
            );

            LOG.infof("Loaded %d language definitions", loaded.size());
            return loaded;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load languages.json");
            return List.of();
        }
    }

    /**
     * Detect language ID from file URI.
     * Matching order (like VSCode):
     * 1. Exact filename match
     * 2. Filename pattern (glob) match
     * 3. Extension match
     * 4. First line match (requires file content)
     */
    public Optional<String> detectLanguage(URI fileUri) {
        return detectLanguage(fileUri, null);
    }

    /**
     * Detect language ID from file URI with optional first line content.
     */
    public Optional<String> detectLanguage(URI fileUri, String firstLine) {
        Path filePath = Paths.get(fileUri);
        String filename = filePath.getFileName().toString();

        // 1. Exact filename match
        for (LanguageDefinition lang : languages) {
            if (lang.filenames().contains(filename)) {
                return Optional.of(lang.id());
            }
        }

        // 2. Filename pattern match (glob)
        for (LanguageDefinition lang : languages) {
            for (String pattern : lang.filenamePatterns()) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                if (matcher.matches(filePath)) {
                    return Optional.of(lang.id());
                }
            }
        }

        // 3. Extension match
        for (LanguageDefinition lang : languages) {
            for (String ext : lang.extensions()) {
                if (filename.endsWith(ext)) {
                    return Optional.of(lang.id());
                }
            }
        }

        // 4. First line match (if content provided)
        if (firstLine != null) {
            for (LanguageDefinition lang : languages) {
                if (lang.firstLine() != null && !lang.firstLine().isEmpty()) {
                    try {
                        if (Pattern.matches(lang.firstLine(), firstLine)) {
                            return Optional.of(lang.id());
                        }
                    } catch (Exception e) {
                        LOG.debugf("Invalid firstLine regex for %s: %s", lang.id(), e.getMessage());
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Create an LSP document from a file URI string.
     * Detects the language ID automatically.
     * The languageId can be null if the language cannot be detected.
     *
     * @param fileUri the file URI as a string
     * @return the LSP document
     */
    public LanguageDocument createDocument(String fileUri) {
        return createDocument(URI.create(fileUri));
    }

    /**
     * Create an LSP document from a file URI.
     * Detects the language ID automatically.
     * The languageId can be null if the language cannot be detected.
     *
     * @param uri the file URI
     * @return the LSP document
     */
    public LanguageDocument createDocument(URI uri) {
        String languageId = detectLanguage(uri).orElse(null);
        return new LanguageDocument(uri, languageId);
    }

    public List<LanguageDefinition> getAllLanguages() {
        return List.copyOf(languages);
    }
}
