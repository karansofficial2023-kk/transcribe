package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SceneStoryboardSourceIntegrityTest {
    private final SceneStoryboardGenerator generator =
        new SceneStoryboardGenerator(null, false, "ltx", true);

    @Test
    void reconstructsSceneNarrationVerbatimFromSentenceNumbers() {
        List<String> source = List.of(
            "Cells connected in series increase voltage.",
            "The positive terminal connects to the next negative terminal.",
            "The current has one path.");
        String response = """
            [
              {"sceneNumber":1,"sceneTitle":"Series Connection","sentenceNumbers":[1,2]},
              {"sceneNumber":2,"sceneTitle":"Current Path","sentenceNumbers":[3]}
            ]
            """;

        List<Scene> scenes = generator.parseScenes(response, source);

        assertEquals(2, scenes.size());
        assertEquals(source.get(0) + " " + source.get(1), scenes.get(0).getNarration());
        assertEquals(source.get(2), scenes.get(1).getNarration());
    }

    @Test
    void invalidScenePlanFallsBackWithoutLosingSourceSentences() {
        List<String> source = List.of("First fact.", "Second fact.", "Third fact.");
        String response = """
            [{"sceneNumber":1,"sceneTitle":"Incomplete","sentenceNumbers":[1,3]}]
            """;

        List<Scene> scenes = generator.parseScenes(response, source);
        String rebuilt = String.join(" ", scenes.stream().map(Scene::getNarration).toList());

        assertEquals(String.join(" ", source), rebuilt);
    }

    @Test
    void missingLlmSegmentsAreRestoredFromSceneNarration() {
        String narration = "First source sentence. Second source sentence.";
        List<SceneSegment> segments = generator.normalizeSegments(List.of(), narration);

        assertEquals(2, segments.size());
        assertEquals("First source sentence.", segments.get(0).getSentence());
        assertEquals("Second source sentence.", segments.get(1).getSentence());
        assertEquals("realistic_labeled_image", segments.get(0).getVisualType());
        assertTrue(segments.get(0).getAssetQualityNotes().contains("label review"));
    }

    @Test
    void orderedRewordedSegmentsKeepTheirVisualAndLabelPlans() {
        SceneSegment first = new SceneSegment();
        first.setSentence("A shortened first idea.");
        first.setVisualType("realistic_labeled_image");
        first.setLabels(List.of("First structure"));
        SceneSegment second = new SceneSegment();
        second.setSentence("A shortened second idea.");
        second.setVisualType("realistic_labeled_image");
        second.setLabels(List.of("Second structure"));

        List<SceneSegment> normalized = generator.normalizeSegments(List.of(first, second),
            "The complete first narration sentence is restored. The complete second narration sentence is restored.");

        assertEquals("The complete first narration sentence is restored.", normalized.get(0).getSentence());
        assertEquals(List.of("First structure"), normalized.get(0).getLabels());
        assertEquals("The complete second narration sentence is restored.", normalized.get(1).getSentence());
        assertEquals(List.of("Second structure"), normalized.get(1).getLabels());
    }

    @Test
    void indicScriptsRemainUsableDuplicateKeys() {
        assertFalse(generator.normalizeForDuplicateKey("மகரந்தச் சேர்க்கை நடைபெறுகிறது").isBlank());
        assertFalse(generator.normalizeForDuplicateKey("परागण एक महत्वपूर्ण प्रक्रिया है").isBlank());
    }

    @Test
    void storyboardCleanupDoesNotApplyTopicSpecificSubstitutions() {
        SceneSegment segment = new SceneSegment();
        segment.setSentence("The flower is called Xora.");
        segment.setHeading("Xora flower");
        Scene scene = new Scene();
        scene.setNarration("The flower is called Xora.");
        scene.setSegments(List.of(segment));

        generator.correctKnownStoryboardTerminology(scene);

        assertEquals("The flower is called Xora.", scene.getNarration());
        assertEquals("The flower is called Xora.", segment.getSentence());
        assertEquals("Xora flower", segment.getHeading());
    }
}
