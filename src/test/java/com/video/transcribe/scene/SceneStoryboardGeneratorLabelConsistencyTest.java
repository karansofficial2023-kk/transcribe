package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        title.setSubtitle("");
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
    void unlabeledComparisonLayoutIsPreserved() {
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

        assertEquals("comparison", segment.getTemplate());
        assertEquals("diagram_overlay", segment.getVisualType());
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

    @Test
    void plainStablePhotoReceivesSubjectAwareLabelReview() {
        SceneSegment segment = new SceneSegment();
        segment.setTemplate("photo");
        segment.setVisualType("realistic_image");

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);

        assertTrue(generator.isLabelPlanningCandidate(segment));
    }

    @Test
    void movingAndFormulaShotsDoNotReceiveStaticLabelReview() {
        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        SceneSegment motion = new SceneSegment();
        motion.setTemplate("video_broll");
        motion.setVisualType("short_motion_clip");
        SceneSegment formula = new SceneSegment();
        formula.setTemplate("formula");
        formula.setVisualType("process_steps");

        assertFalse(generator.isLabelPlanningCandidate(motion));
        assertFalse(generator.isLabelPlanningCandidate(formula));
    }

    @Test
    void abstractConceptsAreRemovedFromScientificArrowTargets() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setSentence("The visible terminals demonstrate a circuit relationship.");
        segment.setTemplate("labeled_image");
        segment.setVisualType("realistic_labeled_image");
        segment.setLabels(List.of("Positive terminal", "Circuit relationship"));
        segment.setLabelPlacements(List.of());
        Scene scene = new Scene();
        scene.setSceneNumber(2);
        scene.setSegments(List.of(segment));

        JsonObject repair = new JsonObject();
        repair.addProperty("rowId", "2.1");
        repair.add("labels", strings("Positive terminal", "Circuit relationship"));
        repair.add("labelPlacements", strings(
            "Positive terminal | raised metal terminal | COORDINATES_PENDING_APPROVED_IMAGE",
            "Circuit relationship | relationship shown by the whole circuit | COORDINATES_PENDING_APPROVED_IMAGE"));
        repair.add("labelTargetKinds", strings("visible_physical_target", "abstract_or_nonpointable"));
        repair.add("pointTargetNames", strings("metal terminal", ""));
        repair.addProperty("presentationMode", "point_labels");
        repair.addProperty("visualSubject", "A close view of a battery and its two metal terminals.");
        repair.addProperty("comfyPrompt", "A close view of a battery and its two metal terminals.");
        JsonArray repairs = new JsonArray();
        repairs.add(repair);

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.applyLabelPlanRepairs(List.of(scene), repairs, Set.of("2.1"));

        assertEquals(List.of("Positive terminal"), segment.getLabels());
        assertEquals(1, segment.getLabelPlacements().size());
        assertTrue(segment.getCoverageNotes().contains("abstract or non-pointable"));
    }

    @Test
    void conceptCannotBorrowDifferentPhysicalStructuresAsItsArrowTarget() {
        assertFalse(SceneStoryboardGenerator.labelNamesPointTarget(
            "spatial adaptation", "anthers and stigma"));
        assertTrue(SceneStoryboardGenerator.labelNamesPointTarget(
            "Positive terminals", "raised metal terminal"));
    }

    @Test
    void labelCannotDriftFromAnAdjacentNarrationRow() {
        assertFalse(SceneStoryboardGenerator.labelSupportedBySentence(
            "Anther", "Three categories compare transfer within and between flowers."));
        assertTrue(SceneStoryboardGenerator.labelSupportedBySentence(
            "Sunflower head", "Sunflowers use a complex reproductive strategy."));
        assertTrue(SceneStoryboardGenerator.labelSupportedBySentence(
            "butterfly", "Beetles, butterflies, moths, and flies visit flowers."));
        assertTrue(SceneStoryboardGenerator.labelSupportedBySentence(
            "fly", "Beetles, butterflies, moths, and flies visit flowers."));
    }

    private JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private void setProductionDefaults(SceneSegment segment, String sentence) {
        segment.setSentence(sentence);
        segment.setHeading("Shot Heading");
        segment.setEstimatedNarrationSeconds(3.0);
        segment.setRecommendedClipSeconds(4.0);
        segment.setMotion("slow_zoom_in");
        segment.setSubtitle(sentence);
        segment.setSubtitleStyle("bottom_band; band_color=black; band_opacity=0.55; text_color=white; font_size=42; max_lines=2; align=center; horizontal_margin=120; bottom_margin=55");
        segment.setVisualSubject("A clearly visible lesson subject fills the frame, with exact structures unobscured and clean margins reserved for overlays.");
        segment.setComfyPrompt("Premium educational documentary frame, sharp native 1920x1080 detail, full-frame visual coverage with no blank card panel, intentional foreground-background separation, camera distance and angle chosen to make the taught evidence clearly inspectable, controlled realistic lighting, natural color and contrast, stable professional composition, and sufficient uncluttered safe margins for renderer overlays. No generated text. No embedded text. No generated labels. No generated arrows. No captions. No watermark. No logo. No border. No UI. No incorrect anatomy or technical structure. No duplicated or malformed objects. No irrelevant background elements. No decorative infographic text. No slide or presentation-card layout.");
    }
}
