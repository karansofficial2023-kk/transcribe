package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class StoryboardQualityGateTest {
    @Test
    void acceptsUnlabeledPhoto() {
        SceneSegment segment = segment("photo", "realistic_image", "photo");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        assertDoesNotThrow(() -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsMissingAssetPath() {
        SceneSegment segment = segment("photo", "realistic_image", "photo");
        segment.setAssetPath("missing_generated_asset.jpg");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsLabelRevealMotionOnUnlabeledPhoto() {
        SceneSegment segment = segment("photo", "realistic_image", "photo");
        segment.setMotion("reveal_in_list_order; completed_frame_hold=2.5s");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsLabeledImageWithEmptyLabels() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void acceptsPendingCoordinatesForDraftImage() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther | upper pollen-bearing structure | COORDINATES_PENDING_APPROVED_IMAGE"));
        pendingLabelContract(segment);

        assertDoesNotThrow(() -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void acceptsCompleteLabeledImage() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther | upper pollen-bearing structure | target=(0.350,0.420)"));
        labeledContract(segment);

        assertDoesNotThrow(() -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsOverlyGenericLabel() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Component"));
        segment.setLabelPlacements(List.of(
            "Component | visible machine component | COORDINATES_PENDING_APPROVED_IMAGE"));
        pendingLabelContract(segment);

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsCombinedScientificLabels() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther and stigma"));
        segment.setLabelPlacements(List.of(
            "Anther and stigma | two visible reproductive structures | COORDINATES_PENDING_APPROVED_IMAGE"));
        pendingLabelContract(segment);

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsNumericCoordinateForUnlockedAsset() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther: box=left; target=(0.35,0.42); target_description=upper pollen-bearing structure"));
        labeledContract(segment);
        segment.setAssetPath("");

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsMissingSourceSentenceCoverage() {
        SceneSegment segment = segment("photo", "realistic_image", "photo");
        segment.setSentence("First sentence.");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        StoryboardDocument storyboard = storyboard(segment);
        storyboard.setSourceText("First sentence. Second sentence.");
        storyboard.getScenes().get(0).setNarration("First sentence.");

        assertThrows(IllegalStateException.class, () -> StoryboardQualityGate.validate(storyboard));
    }

    @Test
    void rejectsEquationRoutedToPhoto() {
        SceneSegment segment = segment("photo", "realistic_image", "photo");
        segment.setSentence("The relation is V = IR.");
        segment.setMotionType("static_image");
        segment.setTool("comfy_image");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setComfyPrompt("");

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void acceptsDeterministicFormulaContract() {
        SceneSegment segment = segment("formula", "process_steps", "animation");
        segment.setSentence("The relation is V = IR.");
        segment.setMotionType("local_animation");
        segment.setTool("manim");
        segment.setFormulaLines(List.of("V = IR"));
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setComfyPrompt("");

        assertDoesNotThrow(() -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    private SceneSegment segment(String template, String visualType, String mediaType) {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setTemplate(template);
        segment.setVisualType(visualType);
        segment.setMediaType(mediaType);
        segment.setSentence("A complete educational sentence.");
        segment.setEstimatedNarrationSeconds(3.0);
        segment.setRecommendedClipSeconds(4.0);
        segment.setMotion("slow_zoom_in");
        segment.setSubtitle("A complete educational sentence.");
        segment.setSubtitleStyle("bottom_band; band_color=black; band_opacity=0.55; text_color=white; font_size=42; max_lines=2; align=center; horizontal_margin=120; bottom_margin=55");
        segment.setHeading("Shot Heading");
        segment.setComfyPrompt("Sharp 1920x1080 educational photography with accurate structures and realistic natural lighting, clear subject separation, sufficient empty margins for overlays. No generated text. No embedded text. No generated labels. No generated arrows. No captions. No watermark. No logo. No border. No UI. No incorrect anatomy or technical structure. No duplicated or malformed objects. No irrelevant background elements. No decorative infographic text. No slide or presentation-card layout.");
        return segment;
    }

    private void labeledContract(SceneSegment segment) {
        segment.setAssetPath(java.nio.file.Path.of("pom.xml").toAbsolutePath().normalize().toString());
        segment.setLabelStyle("high_contrast_box; dark_text; light_background; thin_colored_border; colored_target_dot; 3px_leader_line; sans_serif; 28px_minimum_font; avoid_subject; avoid_title_area; avoid_subtitle_area; avoid_logo_area");
        segment.setMotion("arrow_draw_then_label_fade; reveal_in_list_order; keep_previous_labels_visible; completed_frame_hold=2.5s");
        segment.setRecommendedClipSeconds(4.0);
    }

    private void pendingLabelContract(SceneSegment segment) {
        segment.setAssetPath("");
        segment.setAssetQualityNotes("BLOCK_FINAL_RENDER_UNTIL_LABEL_COORDINATES_ARE_VERIFIED");
        segment.setLabelStyle("high_contrast_box; dark_text; light_background; thin_colored_border; colored_target_dot; 3px_leader_line; sans_serif; 28px_minimum_font; avoid_subject; avoid_title_area; avoid_subtitle_area; avoid_logo_area");
        segment.setMotion("arrow_draw_then_label_fade; reveal_in_list_order; keep_previous_labels_visible; completed_frame_hold=2.5s");
        segment.setRecommendedClipSeconds(4.0);
    }

    private StoryboardDocument storyboard(SceneSegment segment) {
        SceneSegment title = segment("title_card", "title_card", "photo");
        title.setHeading("Lesson Title");
        title.setSubtitle("");
        Scene titleScene = new Scene();
        titleScene.setSceneNumber(1);
        titleScene.setNarration(title.getSentence());
        titleScene.setSegments(List.of(title));
        Scene scene = new Scene();
        scene.setSceneNumber(2);
        scene.setNarration(segment.getSentence());
        scene.setSegments(List.of(segment));
        StoryboardDocument storyboard = new StoryboardDocument();
        storyboard.setTitle("Lesson Title");
        storyboard.setScenes(List.of(titleScene, scene));
        return storyboard;
    }
}
