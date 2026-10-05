package com.video.transcribe.scene;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structured, subject-independent audit of the label and formula contracts. Issues are
 * machine-readable ({shot_id, field, severity, reason, repair}) so the storyboard can be
 * repaired row by row. Nothing here contains lesson-topic vocabulary.
 */
public final class StoryboardContractAudit {
    public static final String PENDING_COORDINATES = "COORDINATES_PENDING_APPROVED_IMAGE";
    static final int PROSE_WORD_LIMIT = 5;

    /** Head nouns that name ideas, not visible physical structures (generic English abstractions). */
    static final Set<String> ABSTRACT_HEAD_NOUNS = Set.of(
        "force", "forces", "process", "processes", "relationship", "relationships", "relation",
        "property", "properties", "concept", "concepts", "idea", "ideas", "scent", "scents",
        "smell", "odor", "odour", "energy", "gravity", "time", "importance", "diversity",
        "adaptation", "evolution", "interaction", "effect", "effects", "influence", "trend",
        "similarity", "difference", "connection", "ancestry", "survival", "success");

    private static final Pattern OPERATOR = Pattern.compile("[=<>+*/^_≈≃∝≤≥→⇌±×÷\\\\]|\\s-\\s");
    private static final Pattern WORD = Pattern.compile("(?<![\\\\\\p{L}])\\p{L}{3,}");

    public record Issue(String shotId, String field, String severity, String reason, String repair) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("shot_id", shotId);
            map.put("field", field);
            map.put("severity", severity);
            map.put("reason", reason);
            map.put("repair", repair);
            return map;
        }
    }

    private StoryboardContractAudit() {
    }

    /** Moves prose out of formula_lines into explain_steps. Returns the number of lines moved. */
    public static int separateFormulaProse(StoryboardDocument storyboard) {
        int moved = 0;
        if (storyboard == null || storyboard.getScenes() == null) return 0;
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                List<String> formulas = segment.getFormulaLines();
                if (formulas == null || formulas.isEmpty()) continue;
                List<String> keep = new ArrayList<>();
                List<String> explain = new ArrayList<>(
                    segment.getExplainSteps() == null ? List.of() : segment.getExplainSteps());
                for (String line : formulas) {
                    if (line != null && isProse(line)) {
                        if (!explain.contains(line.trim())) explain.add(line.trim());
                        moved++;
                    } else {
                        keep.add(line);
                    }
                }
                if (keep.size() != formulas.size()) {
                    segment.setFormulaLines(keep);
                    segment.setExplainSteps(explain);
                }
            }
        }
        return moved;
    }

    public static List<Issue> inspect(StoryboardDocument storyboard) {
        List<Issue> issues = new ArrayList<>();
        if (storyboard == null || storyboard.getScenes() == null) return issues;
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                String id = scene.getSceneNumber() + "." + segment.getSegmentNumber();
                inspectLabels(id, segment, issues);
                inspectFormulas(id, segment, issues);
            }
        }
        return issues;
    }

    static void inspectLabels(String id, SceneSegment segment, List<Issue> issues) {
        List<String> labels = nonBlank(segment.getLabels());
        List<String> placements = nonBlank(segment.getLabelPlacements());
        if (labels.isEmpty()) return;
        if (placements.size() != labels.size()) {
            issues.add(new Issue(id, "label_placement", "error",
                labels.size() + " labels but " + placements.size()
                    + " placements; one placement per label, same order.",
                "Provide one target description per label, in label order."));
        }
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (isAbstractLabel(label)) {
                issues.add(new Issue(id, "labels", "error",
                    "Label '" + label + "' names an abstract idea, not a visible physical target.",
                    "Replace with a visible structure or remove the arrow."));
            }
            if (i < placements.size() && !placementStartsWithLabel(placements.get(i), label)) {
                issues.add(new Issue(id, "label_placement", "error",
                    "Placement " + (i + 1) + " must begin with the exact label '" + label + "'.",
                    "Write '" + label + " | <visible morphology or " + PENDING_COORDINATES + ">'."));
            }
        }
    }

    static void inspectFormulas(String id, SceneSegment segment, List<Issue> issues) {
        List<String> formulas = nonBlank(segment.getFormulaLines());
        for (String line : formulas) {
            if (isProse(line)) {
                issues.add(new Issue(id, "formula_lines", "error",
                    "Formula line contains prose: '" + abbreviate(line) + "'.",
                    "Move the sentence to explain_steps and provide the exact equation."));
                continue;
            }
            String syntax = syntaxProblem(line);
            if (syntax != null) {
                issues.add(new Issue(id, "formula_lines", "error",
                    syntax + " in '" + abbreviate(line) + "'.",
                    "Complete the expression or reject the row."));
            }
        }
        if (("formula".equals(segment.getTemplate()) || "formula".equals(segment.getVisualType())) && formulas.isEmpty()) {
            issues.add(new Issue(id, "formula_lines", "error",
                "Formula shot has no valid formula line.", "Provide the exact equation."));
        }
    }

    /** True when the line reads as a sentence rather than formula syntax. */
    public static boolean isProse(String line) {
        if (line == null || line.isBlank()) return false;
        long words = WORD.matcher(line).results().count();
        boolean hasOperator = OPERATOR.matcher(line).find();
        if (!hasOperator) return words >= 3;
        return words > 2 * PROSE_WORD_LIMIT || (words > PROSE_WORD_LIMIT && line.trim().endsWith("."));
    }

    public static String syntaxProblem(String line) {
        ArrayDeque<Character> stack = new ArrayDeque<>();
        String open = "([{";
        String close = ")]}";
        for (char c : line.toCharArray()) {
            if (open.indexOf(c) >= 0) {
                stack.push(c);
            } else if (close.indexOf(c) >= 0) {
                if (stack.isEmpty() || open.indexOf(stack.pop()) != close.indexOf(c)) return "Unbalanced brackets";
            }
        }
        if (!stack.isEmpty()) return "Unbalanced brackets";
        String trimmed = line.trim();
        if (trimmed.matches(".*[\\^_]\\s*$") || trimmed.matches(".*[\\^_]\\s*[)\\]}].*")
                || trimmed.matches(".*[\\^_]\\s*[\\^_].*")) return "Dangling subscript or superscript";
        return null;
    }

    static boolean isAbstractLabel(String label) {
        String[] words = label.toLowerCase(Locale.ROOT).trim().split("[^\\p{L}]+");
        return words.length > 0 && ABSTRACT_HEAD_NOUNS.contains(words[words.length - 1]);
    }

    static boolean placementStartsWithLabel(String placement, String label) {
        String p = placement.toLowerCase(Locale.ROOT).trim();
        String l = label.toLowerCase(Locale.ROOT).trim();
        if (!p.startsWith(l)) return false;
        String rest = p.substring(l.length()).trim();
        return rest.isEmpty() || rest.startsWith("|") || rest.startsWith(":") || rest.startsWith("-")
            || rest.startsWith("–") || rest.startsWith("—");
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        String t = s.trim();
        return t.length() <= 60 ? t : t.substring(0, 57) + "...";
    }
}
