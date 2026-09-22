package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SceneStoryboardGeneratorLabelConsistencyTest {
    @Test
    void incompleteProcessLabelPlanIsDowngradedBeforeExport() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(2);
        segment.setTemplate("process");
        segment.setVisualType("process_steps");
        segment.setMediaType("photo");
        segment.setMotionType("static_image");
        segment.setLabels(List.of("Positive terminal", "Negative terminal"));
        segment.setLabelPlacements(List.of(
            "Positive terminal: box=left; target=(0.25,0.40); target_description=raised positive battery terminal; "
                + "anchor_source=post_render_visual_verification; on_mismatch=skip_arrow"));

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.finalizeLabelConsistency(segment);

        assertEquals("photo", segment.getTemplate());
        assertEquals("realistic_image", segment.getVisualType());
        assertEquals("photo", segment.getMediaType());
        assertTrue(segment.getLabels().isEmpty());
        assertTrue(segment.getLabelPlacements().isEmpty());

        setProductionDefaults(segment, "Battery terminals are shown clearly.");

        SceneSegment title = new SceneSegment();
        title.setSegmentNumber(1);
        title.setTemplate("title_card");
        title.setVisualType("title_card");
        title.setMediaType("photo");
        setProductionDefaults(title, "Battery Cells in Series.");
        Scene titleScene = new Scene();
        titleScene.setSceneNumber(1);
        titleScene.setNarration(title.getSentence());
        titleScene.setSegments(List.of(title));

        Scene scene = new Scene();
        scene.setSceneNumber(6);
        scene.setNarration(segment.getSentence());
        scene.setSegments(List.of(segment));
        StoryboardDocument document = new StoryboardDocument();
        document.setTitle("Battery Cells in Series");
        title.setHeading("Battery Cells in Series");
        document.setScenes(List.of(titleScene, scene));
        assertDoesNotThrow(() -> StoryboardQualityGate.validate(document));
    }

    @Test
    void combinedPollinationLabelIsSplitIntoAtomicLabels() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setSentence("Pollen moves from the anther to the stigma.");
        segment.setTemplate("labeled_image");
        segment.setVisualType("realistic_labeled_image");
        segment.setMediaType("photo_with_labels");
        segment.setLabels(List.of("anther stigma pollen"));
        segment.setLabelPlacements(List.of());

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.finalizeLabelConsistency(segment);

        assertTrue(segment.getLabels().isEmpty(),
            "An unreviewed combined target must be downgraded instead of drawing a wrong arrow");
        assertEquals("realistic_image", segment.getVisualType());
    }

    @Test
    void diagramWithNoLabelsIsDowngradedBeforeQualityGate() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(3);
        segment.setSentence("The lesson compares two related mechanisms.");
        segment.setTemplate("comparison");
        segment.setVisualType("diagram_overlay");
        segment.setMediaType("photo");
        segment.setMotionType("static_image");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.finalizeLabelConsistency(segment);

        assertEquals("photo", segment.getTemplate());
        assertEquals("realistic_image", segment.getVisualType());
        assertEquals("photo", segment.getMediaType());
        assertTrue(segment.getLabels().isEmpty());
        assertTrue(segment.getLabelPlacements().isEmpty());
    }

    @Test
    void unlabeledShotDoesNotRetainLabelRevealMotion() {
        SceneSegment segment = new SceneSegment();
        segment.setTemplate("photo");
        segment.setVisualType("realistic_image");
        segment.setMediaType("photo");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setMotion("reveal_in_list_order; keep_previous_labels_visible; completed_frame_hold=2.5s");

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.finalizeLabelConsistency(segment);

        assertEquals("slow_zoom_in", segment.getMotion());
        assertFalse(segment.getMotion().contains("label"));
    }

    @Test
    void missingModelSuppliedAssetPathIsCleared() {
        SceneSegment segment = new SceneSegment();
        segment.setAssetPath("invented_asset.png");

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.sanitizeAssetPath(segment);

        assertEquals("", segment.getAssetPath());
        assertTrue(segment.getCoverageNotes().contains("Asset guard"));
    }

    @Test
    void productionTextUsesStablePunctuation() {
        assertEquals("nature's process - clearly explained.",
            SceneStoryboardGenerator.normalizeProductionText("nature\uFFFDs process \u2014 clearly explained\u2026"));
    }

    private void setProductionDefaults(SceneSegment segment, String sentence) {
        segment.setSentence(sentence);
        segment.setEstimatedNarrationSeconds(3.0);
        segment.setRecommendedClipSeconds(4.0);
        segment.setMotion("slow_zoom_in");
        segment.setSubtitle(sentence);
        segment.setSubtitleStyle("bottom_band; band_color=black; band_opacity=0.55; text_color=white; font_size=42; max_lines=2; align=center; horizontal_margin=120; bottom_margin=55");
        segment.setComfyPrompt("Sharp 1920x1080 educational photography with accurate structures and realistic natural lighting, clear subject separation, sufficient empty margins for overlays. No embedded text. No generated labels. No generated arrows. No captions. No watermark. No slide or presentation-card layout.");
    }
}
