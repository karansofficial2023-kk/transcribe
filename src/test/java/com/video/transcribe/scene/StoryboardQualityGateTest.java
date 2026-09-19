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
    void rejectsLabeledImageWithEmptyLabels() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsAutomaticTarget() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther: box=left; target=(auto); target_description=pollen-bearing structure"));

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void acceptsCompleteLabeledImage() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther: box=left; target=(0.35,0.42); target_description=upper pollen-bearing structure; "
                + "anchor_source=post_render_visual_verification; on_mismatch=skip_arrow"));

        assertDoesNotThrow(() -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsOverlyGenericLabel() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Component"));
        segment.setLabelPlacements(List.of(
            "Component: box=left; target=(0.35,0.42); target_description=visible machine component; "
                + "anchor_source=post_render_visual_verification; on_mismatch=skip_arrow"));

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsCombinedScientificLabels() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("anther stigma pollen"));
        segment.setLabelPlacements(List.of(
            "anther stigma pollen: box=left; target=(0.35,0.42); "
                + "target_description=flower reproductive structures; "
                + "anchor_source=post_render_visual_verification; on_mismatch=skip_arrow"));

        assertThrows(IllegalStateException.class,
            () -> StoryboardQualityGate.validate(storyboard(segment)));
    }

    @Test
    void rejectsUnverifiedFinalImageAnchor() {
        SceneSegment segment = segment("labeled_image", "realistic_labeled_image", "photo_with_labels");
        segment.setLabels(List.of("Anther"));
        segment.setLabelPlacements(List.of(
            "Anther: box=left; target=(0.35,0.42); target_description=upper pollen-bearing structure"));

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

    private SceneSegment segment(String template, String visualType, String mediaType) {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setTemplate(template);
        segment.setVisualType(visualType);
        segment.setMediaType(mediaType);
        return segment;
    }

    private StoryboardDocument storyboard(SceneSegment segment) {
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSegments(List.of(segment));
        StoryboardDocument storyboard = new StoryboardDocument();
        storyboard.setScenes(List.of(scene));
        return storyboard;
    }
}
