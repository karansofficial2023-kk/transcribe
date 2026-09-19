package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Scene scene = new Scene();
        scene.setSceneNumber(6);
        scene.setSegments(List.of(segment));
        StoryboardDocument document = new StoryboardDocument();
        document.setScenes(List.of(scene));
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
}
