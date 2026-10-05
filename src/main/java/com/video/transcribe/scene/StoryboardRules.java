package com.video.transcribe.scene;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared deterministic storyboard classification rules. */
final class StoryboardRules {
    private static final Pattern EQUATION = Pattern.compile(
        "(?:[\\p{L}\\p{M}\\p{N}ΔΛλμρσκΩ°_^{}()\\[\\].,+\\-/* ]+)"
            + "(?:=|≈|≃|∝|≤|≥|→|⇌)"
            + "(?:[\\p{L}\\p{M}\\p{N}ΔΛλμρσκΩ°_^{}()\\[\\].,+\\-/* ]+)");
    private static final Pattern CALCULATION_LANGUAGE = Pattern.compile(
        "(?i)\\b(formula|equation|derive|derivation|substitut(?:e|ion)|calculate|calculating|"
            + "calculation|solve for|simplif(?:y|ication)|add(?:ing|ition)?|subtract(?:ing|ion)?|"
            + "sum of|difference between|product of|ratio of|therefore|hence)\\b");

    private StoryboardRules() {
    }

    static boolean requiresFormulaRenderer(SceneSegment segment) {
        if (segment == null) return false;
        if ("title_card".equals(segment.getTemplate())
                || "title_card".equals(segment.getVisualType())) return false;
        if (segment.getFormulaLines() != null && !segment.getFormulaLines().isEmpty()) return true;
        // Classification must come from approved lesson content. Production metadata
        // often mentions symbols, formulas, or rendering instructions generically and
        // must not turn unrelated documentary shots into equation scenes.
        String text = safe(segment.getSentence()).trim();
        if (text.isEmpty()) return false;
        // Only an equation written in the narration itself (or formula lines planned by the visual director) routes a shot
        // to the formula renderer. Calculation vocabulary alone ("sum of", "add") is ordinary prose in every subject.
        return EQUATION.matcher(text).find();
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
        // No equation notation in the sentence: never fall back to prose (formula_lines hold formula syntax only).
        return List.copyOf(matches);
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
