package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tells the video generator HOW each shot is presented on a teacher's board, the way good physics/chemistry lessons are built:
 * a definition in a framed box, points that accumulate as bullets, two things compared in a table filled row by row, a derivation
 * written line by line, a "uses" list shown as a gallery, a summary at the end of a part. Subject-independent: the generator decides
 * where to use it (see subject.py there). Everything comes from the shot's own narration; the model only structures it.
 *
 * Layouts: definition, bullets, table, derivation, diagram, gallery, summary. Deterministic rules decide first; one model call adds
 * table rows and gallery items, and may refine the layout. A failing model call never stops the lesson.
 */
public final class LayoutPlanner {

    private static final Logger logger = LoggerFactory.getLogger(LayoutPlanner.class);
    public static final Set<String> LAYOUTS = Set.of("definition", "bullets", "table", "derivation", "diagram", "gallery", "summary");

    private static final Pattern DEFINITION = Pattern.compile("(?i)\\b(is|are) (called|known as|defined as|termed|referred to as)\\b|\\bis the (property|ability|process|measure|ratio)\\b|\\brefers to\\b|\\bmeans\\b");
    private static final Pattern CONTRAST = Pattern.compile("(?i)\\b(whereas|while|unlike|in contrast|on the other hand|compared (to|with)|versus|vs\\.?|differ)\\b");
    private static final Pattern SUMMARY = Pattern.compile("(?i)\\b(to summari[sz]e|in summary|summary|to conclude|in conclusion|recap)\\b");
    private static final Pattern USES = Pattern.compile("(?i)\\b(used (as|in|for)|uses of|applications? (of|in|include))\\b");

    public interface Model {
        String generate(String system, String user) throws java.io.IOException;
    }

    private LayoutPlanner() {
    }

    /** Rule-based layout of one shot (also the fallback when the model is unavailable). */
    public static String ruleLayout(SceneSegment segment) {
        String text = segment.getSentence() == null ? "" : segment.getSentence();
        if (segment.getFormulaLines() != null && !segment.getFormulaLines().isEmpty()) return "derivation";
        if (segment.getLabels() != null && !segment.getLabels().isEmpty()) return "diagram";
        if (SUMMARY.matcher(text).find()) return "summary";
        if (CONTRAST.matcher(text).find()) return "table";
        if (USES.matcher(text).find() && text.split(",").length >= 3) return "gallery";
        if (DEFINITION.matcher(text).find()) return "definition";
        String visual = segment.getVisualType() == null ? "" : segment.getVisualType();
        if (visual.contains("image") || visual.contains("motion")) return "diagram";
        return "bullets";
    }

    public static int plan(StoryboardDocument storyboard, Model model) {
        if (storyboard.getScenes() == null) return 0;
        Map<String, SceneSegment> byId = new LinkedHashMap<>();
        StringBuilder listing = new StringBuilder();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                segment.setLayout(ruleLayout(segment));
                String id = scene.getSceneNumber() + "." + segment.getSegmentNumber();
                byId.put(id, segment);
                listing.append(id).append(" [").append(scene.getSceneTitle()).append("] ").append(segment.getSentence()).append('\n');
            }
        }
        if (model == null || byId.isEmpty()) return byId.size();
        String answer;
        try {
            answer = model.generate("You lay out the shots of a lesson titled '" + storyboard.getTitle() + "' on a teacher's board. For each "
                + "shot choose ONE layout: definition (one sentence that defines a term), bullets (points that add up), table (the shot "
                + "compares two things), derivation (an equation is developed), diagram (a picture or apparatus is the point), gallery "
                + "(a list of uses or examples), summary (a recap). For table shots give the two column headers and rows taken ONLY from "
                + "the narration, as \"left | right\" strings, first row = headers. For gallery shots give the 2-6 short item names from "
                + "the narration. Never invent content. Return JSON {\"shots\": [{\"id\": \"1.2\", \"layout\": \"...\", \"rows\": [], "
                + "\"items\": []}]}.", listing.toString());
        } catch (Exception e) {
            logger.warn("Layout planning used the rules only (model unavailable): {}", e.getMessage());
            return byId.size();
        }
        try {
            int start = answer.indexOf('{');
            JsonObject json = JsonParser.parseString(answer.substring(start, answer.lastIndexOf('}') + 1)).getAsJsonObject();
            JsonArray shots = json.getAsJsonArray("shots");
            int refined = 0;
            for (JsonElement element : shots == null ? new JsonArray() : shots) {
                if (!element.isJsonObject()) continue;
                JsonObject shot = element.getAsJsonObject();
                SceneSegment segment = byId.get(text(shot, "id"));
                if (segment == null) continue;
                String layout = text(shot, "layout").toLowerCase(Locale.ROOT);
                boolean hasFormula = segment.getFormulaLines() != null && !segment.getFormulaLines().isEmpty();
                String sentence = segment.getSentence() == null ? "" : segment.getSentence();
                if ("summary".equals(layout) && !SUMMARY.matcher(sentence).find()) {
                    continue;                  // "Right?" or "Therefore rho stays constant" is not a recap: no "Summary" heading on the board
                }
                if (LAYOUTS.contains(layout) && !hasFormula && !"derivation".equals(layout)) {      // equations always stay a derivation
                    segment.setLayout(layout);
                    refined++;
                }
                List<String> rows = list(shot, "rows").stream().filter(r -> r.contains("|")).toList();
                if ("table".equals(segment.getLayout())) {
                    if (rows.size() >= 2) segment.setTableRows(rows);
                    else segment.setLayout("bullets");                    // a table without rows is not a table
                }
                List<String> items = list(shot, "items");
                if ("gallery".equals(segment.getLayout())) {
                    if (items.size() >= 2) segment.setGalleryItems(items.subList(0, Math.min(6, items.size())));
                    else segment.setLayout("bullets");
                }
            }
            logger.info("Layout plan: {} shot(s), {} refined by the model", byId.size(), refined);
        } catch (RuntimeException e) {
            logger.warn("Layout planning used the rules only (unusable answer)");
        }
        return byId.size();
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static List<String> list(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        JsonElement value = json.get(key);
        if (value instanceof JsonArray array) {
            for (JsonElement item : array) {
                if (item.isJsonPrimitive() && !item.getAsString().isBlank()) out.add(item.getAsString().trim());
            }
        }
        return out;
    }
}
