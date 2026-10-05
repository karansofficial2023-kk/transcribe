package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CircuitSurvivalTest {
    private static SceneSegment cell() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(2);
        segment.setSentence("Let's drag the labels into their respective boxes: the terminal connected to the battery's positive terminal "
            + "is called the anode, while the one connected to the negative terminal is known as the cathode.");
        segment.setTemplate("circuit");
        segment.setVisualType("circuit");
        segment.setMediaType("animation");
        segment.setMotionType("local_animation");
        segment.setTool("pillow_opencv");
        segment.setColumns(List.of("Cell | electrolysis: battery; anode silver rod; cathode keychain; electrolyte silver nitrate solution"));
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        return segment;
    }

    @Test
    void labelConsistencyKeepsTheCircuit() {
        SceneSegment segment = cell();
        new SceneStoryboardGenerator(null, false, "ltx", true).finalizeLabelConsistency(segment);
        assertEquals("circuit", segment.getVisualType());
    }

    @Test
    void narrationAlignmentKeepsTheCircuit() {
        SceneSegment segment = cell();
        new SceneStoryboardGenerator(null, false, "ltx", true).enforceNarrationVisualAlignment(segment);
        assertEquals("circuit", segment.getVisualType());
    }

    @Test
    void formulaRoutingKeepsTheCircuit() {
        SceneSegment segment = cell();
        new SceneStoryboardGenerator(null, false, "ltx", true).enforceFormulaRouting(segment);
        assertEquals("circuit", segment.getVisualType());
    }

    @Test
    void circuitSurvivesTheFullFinalContractWhenAnimationIsDisabled() {
        SceneSegment title = new SceneSegment();
        title.setSegmentNumber(1);
        title.setSentence("All About Electroplating.");
        title.setTemplate("title_card");
        title.setVisualType("title_card");
        title.setMediaType("photo");
        Scene titleScene = new Scene();
        titleScene.setSceneNumber(1);
        titleScene.setNarration(title.getSentence());
        titleScene.setSegments(List.of(title));
        SceneSegment cell = cell();
        cell.setSegmentNumber(1);
        Scene scene = new Scene();
        scene.setSceneNumber(3);
        scene.setSceneTitle("Electroplating equipment");
        scene.setNarration(cell.getSentence());
        scene.setSegments(List.of(cell));
        StoryboardDocument document = new StoryboardDocument();
        document.setTitle("All About Electroplating");
        document.setScenes(List.of(titleScene, scene));
        new SceneStoryboardGenerator(null, false, "ltx", true).finalizeProductionContract(document);
        assertEquals("circuit", cell.getVisualType());
        assertEquals("circuit", cell.getTemplate());
        assertEquals(1, cell.getColumns().size());
    }
}
