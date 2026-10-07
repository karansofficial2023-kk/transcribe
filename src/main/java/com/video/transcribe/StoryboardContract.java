package com.video.transcribe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

/**
 * Single source of truth for the per-shot production record. The DOCX exporter and the
 * JSON contract both read these fields, so the two representations cannot drift.
 */
public final class StoryboardContract {
    public static final String SCHEMA_VERSION = "2.0";
    /** Fields rendered as DOCX rows only when non-empty. */
    public static final List<String> OPTIONAL_LIST_FIELDS =
        List.of("formula_lines", "explain_steps", "steps", "columns", "table_rows", "gallery_items");

    private StoryboardContract() {
    }

    /** Language package handed downstream (subtitle wrapping, TTS voice, fonts). */
    public record LanguagePackage(String bcp47, String script, String direction,
                                  String voicePreference, List<String> glossary) {
        public static LanguagePackage defaults() {
            return new LanguagePackage("en-IN", "Latn", "ltr", "en-IN-NeerjaNeural", List.of());
        }

        /** Language package from the ASR-detected ISO 639-1 code; unknown codes keep the English defaults. */
        public static LanguagePackage forWhisperCode(String code) {
            if (code == null) return defaults();
            String c = code.trim().toLowerCase(java.util.Locale.ROOT);
            if ("en".equals(c)) return defaults();
            return LanguageSupport.byCode(c)
                .map(language -> new LanguagePackage(language.bcp47(), language.iso15924(), language.rtl() ? "rtl" : "ltr",
                    language.voice(), List.of()))
                .orElseGet(() -> new LanguagePackage(c, "Latn", "ltr", "", List.of()));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bcp47", bcp47);
            map.put("script", script);
            map.put("direction", direction);
            map.put("voice_preference", voicePreference);
            map.put("glossary", glossary == null ? List.of() : glossary);
            return map;
        }
    }

    /** Ordered shot record. List fields are List of String, duration is a Double, the rest are Strings. */
    public static Map<String, Object> shotRecord(int sceneNumber, SceneSegment segment) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("shot_id", sceneNumber + "." + segment.getSegmentNumber());
        f.put("heading", text(segment.getHeading()));          // on-screen title for title cards, concise topic for the others
        f.put("narration", text(segment.getSentence()));
        f.put("duration", segment.getRecommendedClipSeconds());
        f.put("visual_type", text(segment.getVisualType()));
        f.put("media_type", productionMediaType(segment));
        f.put("image_requirement", text(segment.getVisualSubject()));
        f.put("image_prompt", text(segment.getComfyPrompt()));
        f.put("labels", list(segment.getLabels()));
        f.put("label_placement", list(segment.getLabelPlacements()));
        f.put("label_style", text(segment.getLabelStyle()));
        f.put("motion", text(segment.getMotion()));
        f.put("subtitle", text(segment.getSubtitle()));
        f.put("subtitle_style", text(segment.getSubtitleStyle()));
        f.put("asset_path", text(segment.getAssetPath()));
        f.put("wan_video_prompt", segment.getShot() == null ? "" : text(segment.getShot().getPrompt()));
        f.put("ltx_video_prompt", segment.getLtxShot() == null ? "" : text(segment.getLtxShot().getPrompt()));
        f.put("animate", segment.isAnimate() ? "yes" : "no");
        f.put("layout", text(segment.getLayout()));                  // board layout (LayoutPlanner)
        f.put("table_rows", list(segment.getTableRows()));
        f.put("gallery_items", list(segment.getGalleryItems()));     // bring the approved picture to life (LTX image-to-video); see MotionPlanner
        f.put("formula_lines", list(segment.getFormulaLines()));
        f.put("explain_steps", list(segment.getExplainSteps()));
        f.put("steps", list(segment.getSteps()));
        f.put("columns", list(segment.getColumns()));
        // Never rendered on screen: QA notes, coverage and asset-quality remarks only.
        f.put("review_notes", joinNonBlank(text(segment.getCoverageNotes()), text(segment.getAssetQualityNotes())));
        return f;
    }

    /** Top-level contract document for the video generator. */
    public static Map<String, Object> toContract(StoryboardDocument storyboard, LanguagePackage language) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("title", text(storyboard.getTitle()));
        root.put("subject", text(storyboard.getSubject()));
        root.put("topic", text(storyboard.getTopic()));
        root.put("language", (language == null ? LanguagePackage.defaults() : language).toMap());
        List<Map<String, Object>> scenes = new ArrayList<>();
        com.video.transcribe.scene.MotionPlanner.plan(storyboard.getScenes());
        if (storyboard.getScenes() != null) {
            for (Scene scene : storyboard.getScenes()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("scene_number", scene.getSceneNumber());
                s.put("scene_title", text(scene.getSceneTitle()));
                s.put("narration", text(scene.getNarration()));
                List<Map<String, Object>> shots = new ArrayList<>();
                if (scene.getSegments() != null) {
                    for (SceneSegment segment : scene.getSegments()) {
                        shots.add(shotRecord(scene.getSceneNumber(), segment));
                    }
                }
                s.put("shots", shots);
                scenes.add(s);
            }
        }
        root.put("scenes", scenes);
        return root;
    }

    static String productionMediaType(SceneSegment segment) {
        if (segment.getAssetPath() != null && !segment.getAssetPath().isBlank()) return "supplied image";
        if ("manim".equals(segment.getTool())) return "Manim animation";
        if ("short_motion_clip".equals(segment.getVisualType())) {
            if (segment.getLtxShot() != null || "ltx_video".equals(segment.getTool())) return "LTX clip";
            return "Wan clip";
        }
        if ("diagram_overlay".equals(segment.getVisualType())
                || "process_steps".equals(segment.getVisualType())) return "local diagram";
        if ("split_screen".equals(segment.getVisualType())) return "FFmpeg composite";
        return "generated still";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> list(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) out.add(value.trim());
            }
        }
        return out;
    }

    private static String joinNonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim());
        }
        return String.join("\n", out);
    }
}
