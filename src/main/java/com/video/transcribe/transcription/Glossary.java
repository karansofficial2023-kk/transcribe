package com.video.transcribe.transcription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Teacher-maintained spelling corrections ({"misrecognised": "correct"}) applied to ASR text before any LLM sees it. Speech
 * recognition regularly mis-spells subject terms, above all in Indic scripts, and no language model reliably repairs them.
 * Sources, later ones winning: {@code glossary/<lang>.json} (project), {@code <materials>/glossary.json} (shared),
 * {@code <materials>/<base>_glossary.json} (this lesson). Matching is whole-word and combining-mark aware.
 */
public final class Glossary {
    private static final Logger logger = LoggerFactory.getLogger(Glossary.class);
    private static final Gson GSON = new Gson();
    private final Map<String, String> entries = new LinkedHashMap<>();

    public static Glossary load(String language, String materialsDir, String baseName) {
        Glossary glossary = new Glossary();
        glossary.read(Paths.get("glossary", (language == null || language.isBlank() ? "xx" : language) + ".json"));
        if (materialsDir != null && !materialsDir.isBlank()) {
            glossary.read(Paths.get(materialsDir, "glossary.json"));
            glossary.read(Paths.get(materialsDir, baseName + "_glossary.json"));
        }
        return glossary;
    }

    private void read(Path path) {
        if (!Files.isRegularFile(path)) return;
        try {
            Map<String, String> loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() { }.getType());
            if (loaded != null) {
                loaded.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null);   // a null correction would crash apply()
                entries.putAll(loaded);
                logger.info("Loaded {} glossary corrections from {}", loaded.size(), path);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Glossary {} ignored: {}", path, e.getMessage());
        }
    }

    public int size() {
        return entries.size();
    }

    /** The correct spellings, used as recognition hotwords so the ASR is biased towards them instead of only repaired afterwards. */
    public java.util.List<String> hotwords() {
        return entries.values().stream().filter(v -> v != null && !v.isBlank()).distinct().limit(50).toList();
    }

    public String apply(String text) {
        if (text == null || entries.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey().isBlank()) continue;
            Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(entry.getKey()) + "(?![\\p{L}\\p{M}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }
}
