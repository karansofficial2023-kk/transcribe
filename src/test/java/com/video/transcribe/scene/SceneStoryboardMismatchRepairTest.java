package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SceneStoryboardMismatchRepairTest {
    @Test
    void finalContractReplacesUnrelatedVisualAfterPositionalNarrationRepair() {
        SceneSegment opening = new SceneSegment();
        opening.setSegmentNumber(1);
        opening.setSentence("Plant reproduction begins with pollen transfer.");
        opening.setTemplate("photo");
        opening.setVisualType("realistic_image");
        opening.setMediaType("photo");
        opening.setMotionType("static_image");

        SceneSegment repaired = new SceneSegment();
        repaired.setSegmentNumber(1);
        repaired.setSentence("A flower keeps its receptive stigma above the pollen-bearing anther.");
        repaired.setTemplate("video_broll");
        repaired.setVisualType("short_motion_clip");
        repaired.setMediaType("wan_video");
        repaired.setMotionType("wan_video");
        repaired.setVisualSubject("Human heart and aorta");
        repaired.setVisualAnimation("Blood leaves the left ventricle through the aorta.");
        repaired.setComfyPrompt("Realistic human heart with pulmonary circulation.");
        repaired.setCoverageNotes("Source-coverage guard: restored the exact narration sentence while preserving the ordered visual plan.");

        Scene firstScene = new Scene();
        firstScene.setSceneNumber(1);
        firstScene.setSceneTitle("Plant Reproduction");
        firstScene.setNarration(opening.getSentence());
        firstScene.setSegments(List.of(opening));

        Scene contentScene = new Scene();
        contentScene.setSceneNumber(2);
        contentScene.setSceneTitle("Visible Flower Structures");
        contentScene.setNarration(repaired.getSentence());
        contentScene.setSegments(List.of(repaired));

        StoryboardDocument document = new StoryboardDocument();
        document.setTitle("Plant Reproduction");
        document.setScenes(List.of(firstScene, contentScene));

        new SceneStoryboardGenerator(null, false, "ltx", true)
            .finalizeProductionContract(document);

        assertEquals("realistic_image", repaired.getVisualType());
        assertFalse(repaired.getVisualSubject().toLowerCase().contains("heart"));
        assertFalse(repaired.getComfyPrompt().toLowerCase().contains("aorta"));
        assertTrue(repaired.getVisualSubject().contains("Plant Reproduction"));
        assertTrue(repaired.getVisualSubject().contains("receptive stigma"));
    }
}
