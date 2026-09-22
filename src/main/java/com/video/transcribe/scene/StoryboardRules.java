package com.video.transcribe.scene;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared deterministic storyboard classification rules. */
final class StoryboardRules {
    private static final Pattern EQUATION = Pattern.compile(
        "(?:[\\p{L}\\p{N}ΔΛλμρσκΩ°_^{}()\\[\\].,+\\-/* ]+)"
            + "(?:=|≈|≃|∝|≤|≥|→|⇌)"
            + "(?:[\\p{L}\\p{N}ΔΛλμρσκΩ°_^{}()\\[\\].,+\\-/* ]+)");
    private static final Pattern CALCULATION_LANGUAGE = Pattern.compile(
        "(?i)\\b(formula|equation|derive|derivation|substitut(?:e|ion)|calculate|solve for|"
            + "simplif(?:y|ication)|therefore|hence)\\b");

    private StoryboardRules() {
    }

    static boolean requiresFormulaRenderer(SceneSegment segment) {
        if (segment == null) return false;
        if (segment.getFormulaLines() != null && !segment.getFormulaLines().isEmpty()) return true;
        String text = String.join(" ", List.of(
            safe(segment.getSentence()), safe(segment.getHeading()),
            safe(segment.getVisualAnimation()), safe(segment.getLocalAnimation()))).trim();
        if (text.isEmpty()) return false;
        return EQUATION.matcher(text).find()
            || (CALCULATION_LANGUAGE.matcher(text).find() && containsMathSignal(text));
    }

    static List<String> extractFormulaLines(String sentence) {
        if (sentence == null || sentence.isBlank()) return List.of();
        var matches = new java.util.ArrayList<String>();
        var matcher = EQUATION.matcher(sentence);
        while (matcher.find()) {
            String value = matcher.group().replaceAll("\\s+", " ").trim();
            value = value.replaceFirst("(?i)^.*?(?:equation|formula|gives|is|becomes)\\s*:?\\s+(?=[^=]{1,80}=)", "");
            if (!value.isBlank()) matches.add(value);
        }
        if (!matches.isEmpty()) return List.copyOf(matches);
        return CALCULATION_LANGUAGE.matcher(sentence).find() ? List.of(sentence.trim()) : List.of();
    }

    private static boolean containsMathSignal(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return text.matches(".*[+\\-/*^].*")
            || lower.matches(".*\\b\\d+(?:\\.\\d+)?\\b.*")
            || lower.matches(".*\\b(?:sin|cos|tan|log|ln|sqrt|delta|lambda|mole|volt|ampere|ohm)\\b.*");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
