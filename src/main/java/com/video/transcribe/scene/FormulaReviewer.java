package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks every equation written into the storyboard before it reaches the screen. The accuracy gates verify the narration; the
 * formula lines are written later by the storyboard model and were never checked, so a wrong law ("Λ(weak) = Λ(strong) − Λ(strong)")
 * or a garbled symbol ("Λ³ₘ" for "Λ°ₘ", "lambda" for λ) could reach the video. A teacher would never write such a line on the board.
 * <ol>
 * <li>Syntax repair (deterministic): Greek letter names become LaTeX symbols, stray Markdown and dollar signs are removed.</li>
 * <li>Independent review (language model, reviewer role): each line is judged against its own narration and textbook correctness
 * at the lesson's level: ok, fix (with the corrected line) or remove. Removed or doubtful lines are listed for the teacher.</li>
 * </ol>
 * Subject-independent: the same check serves physics, chemistry and mathematics.
 */
public final class FormulaReviewer {

    private static final Logger logger = LoggerFactory.getLogger(FormulaReviewer.class);

    public interface Model {
        String generate(String system, String user) throws java.io.IOException;
    }

    public record Finding(String shot, String original, String verdict, String result, String reason) {
    }

    private static final String[] GREEK = {"alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "pi", "rho", "sigma",
        "tau", "phi", "omega", "Gamma", "Delta", "Theta", "Lambda", "Pi", "Sigma", "Phi", "Omega"};
    private static final Pattern GREEK_WORD;

    static {
        GREEK_WORD = Pattern.compile("(?<![\\\\A-Za-z])(" + String.join("|", GREEK) + ")(?=[_^({\\s=+\\-*/)]|$)");
    }

    private FormulaReviewer() {
    }

    /** Spelled-out Greek letters become symbols ("lambda_m^0" -> "\lambda_m^0"); Markdown/dollar wrappers are removed. */
    public static String repairSyntax(String line) {
        if (line == null) return "";
        String value = line.trim().replaceAll("^\\$+|\\$+$", "").replace("**", "").replaceAll("^`+|`+$", "").trim();
        Matcher m = GREEK_WORD.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("\\" + m.group(1)));
        m.appendTail(out);
        return out.toString();
    }

    public static List<Finding> review(StoryboardDocument storyboard, Model model) {
        List<Finding> findings = new ArrayList<>();
        if (storyboard.getScenes() == null) return findings;
        Map<String, SceneSegment> byId = new LinkedHashMap<>();
        StringBuilder listing = new StringBuilder();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                List<String> lines = segment.getFormulaLines();
                if (lines == null || lines.isEmpty()) continue;
                List<String> repaired = new ArrayList<>();
                for (String line : lines) {
                    String fixed = repairSyntax(line);
                    if (!fixed.equals(line)) findings.add(new Finding(id(scene, segment), line, "syntax", fixed, "symbol spelled out as a word"));
                    if (!fixed.isBlank()) repaired.add(fixed);
                }
                segment.setFormulaLines(repaired);
                for (int i = 0; i < repaired.size(); i++) {
                    String key = id(scene, segment) + "#" + i;
                    byId.put(key, segment);
                    listing.append(key).append(" | narration: ").append(segment.getSentence()).append(" | formula: ").append(repaired.get(i)).append('\n');
                }
            }
        }
        if (byId.isEmpty() || model == null) return findings;

        String answer;
        try {
            answer = model.generate("You are a strict subject-matter reviewer (physics, chemistry and mathematics teacher) checking the equations "
                + "that will be written on screen in a lesson titled '" + storyboard.getTitle() + "'. You did not write them. For EACH line "
                + "decide: \"ok\" when it is correct textbook science/mathematics and matches its narration; \"fix\" when it is meant correctly "
                + "but has an error (wrong sign, wrong term, wrong symbol, a digit where a degree sign or subscript belongs, unbalanced "
                + "chemical equation) - give the corrected line in the same LaTeX style; \"remove\" when it is wrong and cannot be repaired "
                + "from its narration. Never invent content the narration does not support. Return JSON {\"items\": [{\"id\": \"...\", "
                + "\"verdict\": \"ok|fix|remove\", \"formula\": \"corrected line or empty\", \"reason\": \"short\"}]}.", listing.toString());
        } catch (Exception e) {
            logger.warn("Formula review skipped (model unavailable): {}", e.getMessage());
            findings.add(new Finding("all", "", "not_reviewed", "", "the equation review could not run; a teacher must check every equation"));
            return findings;
        }
        JsonArray items;
        try {
            int start = answer.indexOf('{');
            JsonObject json = JsonParser.parseString(answer.substring(start, answer.lastIndexOf('}') + 1)).getAsJsonObject();
            items = json.getAsJsonArray("items");
        } catch (RuntimeException e) {
            findings.add(new Finding("all", "", "not_reviewed", "", "the equation review returned no usable answer; check every equation"));
            return findings;
        }
        Map<SceneSegment, List<String>> updated = new LinkedHashMap<>();
        byId.values().forEach(segment -> updated.putIfAbsent(segment, new ArrayList<>(segment.getFormulaLines())));
        Map<SceneSegment, List<Integer>> removals = new LinkedHashMap<>();
        for (JsonElement element : items == null ? new JsonArray() : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String key = text(item, "id");
            SceneSegment segment = byId.get(key);
            if (segment == null) continue;
            int index = Integer.parseInt(key.substring(key.lastIndexOf('#') + 1));
            String verdict = text(item, "verdict").toLowerCase(Locale.ROOT);
            String original = updated.get(segment).get(index);
            String reason = text(item, "reason");
            if ("fix".equals(verdict)) {
                String corrected = repairSyntax(text(item, "formula"));
                if (!corrected.isBlank() && !corrected.equals(original)) {
                    updated.get(segment).set(index, corrected);
                    findings.add(new Finding(key, original, "fixed", corrected, reason));
                }
            } else if ("remove".equals(verdict)) {
                removals.computeIfAbsent(segment, s -> new ArrayList<>()).add(index);
                findings.add(new Finding(key, original, "removed", "", reason));
            }
        }
        for (Map.Entry<SceneSegment, List<String>> entry : updated.entrySet()) {
            List<String> lines = new ArrayList<>();
            List<Integer> drop = removals.getOrDefault(entry.getKey(), List.of());
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (!drop.contains(i)) lines.add(entry.getValue().get(i));
            }
            entry.getKey().setFormulaLines(lines);
        }
        long changed = findings.stream().filter(f -> !"syntax".equals(f.verdict())).count();
        logger.info("Formula review: {} equation line(s) checked, {} fixed or removed", byId.size(), changed);
        return findings;
    }

    private static String id(Scene scene, SceneSegment segment) {
        return scene.getSceneNumber() + "." + segment.getSegmentNumber();
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }
}
