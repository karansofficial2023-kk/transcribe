package com.video.transcribe.transcription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Preferred translated forms for subject terms, selected by the original source line. */
public final class TerminologyGlossary {
    public record Term(String source, String preferred, List<String> aliases, String subject, List<String> sourceExcludes) { }
    private static final Gson GSON = new Gson();
    private final List<Term> terms = new ArrayList<>();

    public static TerminologyGlossary load(String language, String materialsDir, String baseName) {
        TerminologyGlossary out = new TerminologyGlossary();
        String code = language == null || language.isBlank() ? "xx" : language.toLowerCase(Locale.ROOT);
        Path dir = Paths.get("terminology", code);
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(out::read);
            } catch (IOException ignored) { }
        }
        out.read(Paths.get("terminology", code + ".json"));
        if (materialsDir != null && !materialsDir.isBlank()) {
            out.read(Paths.get(materialsDir, baseName + "_terminology_" + code + ".json"));
            out.read(Paths.get(materialsDir, "terminology_" + code + ".json"));
        }
        return out;
    }

    private void read(Path path) {
        if (!Files.isRegularFile(path)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject value = entry.getValue().getAsJsonObject();
                String preferred = value.has("preferred") ? value.get("preferred").getAsString() : "";
                if (preferred.isBlank()) continue;
                List<String> aliases = new ArrayList<>();
                if (value.has("aliases") && value.get("aliases").isJsonArray())
                    value.getAsJsonArray("aliases").forEach(a -> aliases.add(a.getAsString()));
                String subject = value.has("subject") ? value.get("subject").getAsString() : "general";
                List<String> sourceExcludes = new ArrayList<>();
                if (value.has("source_excludes") && value.get("source_excludes").isJsonArray())
                    value.getAsJsonArray("source_excludes").forEach(item -> sourceExcludes.add(item.getAsString()));
                terms.removeIf(existing -> existing.source().equalsIgnoreCase(entry.getKey()));
                terms.add(new Term(entry.getKey(), preferred, aliases, subject, sourceExcludes));
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    /** When a source line uses a term, normalize a known alternate target-language form to the chosen school term. */
    public String normalize(String source, String translated) {
        if (source == null || translated == null) return translated;
        String result = translated;
        for (Term term : terms) {
            if (!containsWord(source, term.source())) continue;
            if (term.sourceExcludes().stream().anyMatch(excluded -> containsWord(source, excluded))) continue;
            for (String alias : term.aliases()) {
                if (!alias.isBlank() && containsWord(result, alias))
                    result = replaceWord(result, alias, term.preferred());
            }
        }
        return result;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{M}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
    private static String replaceWord(String text, String word, String replacement) {
        return Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{M}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }
}
