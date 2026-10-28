package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NarrationSanityCheckTest {

    private static final String LESSON = "The Male Reproductive System";
    private static final String SCENE = "Organs of the male reproductive system";

    private static List<String> signalsFor(String sentence) {
        List<String> scene = List.of("The testes produce sperm in the male reproductive system.", sentence,
            "Sperm travel through the vas deferens to the urethra.", "The prostate gland adds fluid to the sperm.");
        return NarrationSanityCheck.signals(LESSON, SCENE, scene, 1);
    }

    @Test
    void garbledAndOffTopicLinesAreFlagged() {
        assertFalse(signalsFor("Stop imbibing.").isEmpty());
        assertFalse(signalsFor("Both are acidic.").isEmpty());
    }

    @Test
    void ordinaryLinesPass() {
        assertTrue(signalsFor("The testes produce sperm.").isEmpty());
        List<String> bio = List.of("Cells divide.", "Every cell comes from another cell.", "Mitosis gives two identical daughter cells.");
        assertTrue(NarrationSanityCheck.signals("Cell division", "Cells and growth", bio, 0).isEmpty());
    }

    @Test
    void theWholeLessonIsTheContextAndAnOffTopicNeighbourCannotVouchForALine() {
        String lesson = "Female Reproductive Anatomy";
        List<String> all = List.of("The uterus receives the egg.", "The ovaries release an egg each month.", "The vagina leads to the cervix.",
            "Did you know that the cervix shares a trait with tomatoes?", "Both are acidic.", "The uterus is a muscular organ.");
        // "uterus" occurs far away in the lesson, so an ordinary short sentence in a tiny scene is fine
        assertTrue(NarrationSanityCheck.signals(lesson, "Organs", List.of("The uterus is a muscular organ."), 0, all, 5).isEmpty());
        // "Both are acidic" shares "acidic" with nothing except the tomato aside next to it, and that aside does not count
        assertFalse(NarrationSanityCheck.signals(lesson, "Organs", List.of("Both are acidic."), 0, all, 4).isEmpty());
    }

    @Test
    void transitionsAndSentencesEndingInANumberOrAVariableAreNotFlagged() {
        List<String> scene = List.of("The testes produce sperm.", "Hello!", "But why is this the case?", "We believe in you.");
        for (int i = 1; i < scene.size(); i++) {
            assertTrue(NarrationSanityCheck.signals(LESSON, SCENE, scene, i).isEmpty(), scene.get(i));
        }
        // a sentence that ends in a number or in a variable letter does not end on a dangling "and" / "a"
        List<String> ends = List.of("Puberty begins in girls between 8 and 13 and in boys between 9 and 14.", "Resistance equals Rho times L divided by area A.");
        for (int i = 0; i < ends.size(); i++) {
            assertFalse(NarrationSanityCheck.signals(LESSON, SCENE, ends, i).contains("ends on a dangling connective"), ends.get(i));
        }
    }

    @Test
    void repeatedAndDanglingSentencesAreFlagged() {
        List<String> scene = List.of("Sperm are made in the testes.", "Sperm are made in the testes.", "They mature in the epididymis and");
        assertTrue(NarrationSanityCheck.signals(LESSON, SCENE, scene, 1).contains("repeats the previous sentence"));
        assertTrue(NarrationSanityCheck.signals(LESSON, SCENE, scene, 2).contains("ends on a dangling connective"));
    }

    @Test
    void checkNeverChangesNarrationAndSurvivesADeadModel() {
        SceneSegment a = new SceneSegment();
        a.setSegmentNumber(1);
        a.setSentence("The testes produce sperm in the male reproductive system.");
        SceneSegment b = new SceneSegment();
        b.setSegmentNumber(2);
        b.setSentence("Stop imbibing.");
        SceneSegment c = new SceneSegment();
        c.setSegmentNumber(3);
        c.setSentence("Sperm travel through the vas deferens to the urethra.");
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSceneTitle(SCENE);
        scene.setSegments(new ArrayList<>(List.of(a, b, c)));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle(LESSON);
        doc.setScenes(new ArrayList<>(List.of(scene)));

        List<NarrationSanityCheck.Finding> findings = NarrationSanityCheck.check(doc, (s, u) -> { throw new java.io.IOException("Ollama down"); });
        assertEquals(1, findings.size());
        assertEquals("not_reviewed", findings.get(0).llm_verdict());
        assertEquals("Stop imbibing.", b.getSentence());
        assertTrue(b.getCoverageNotes().startsWith("NARRATION CHECK:"));
        assertTrue(b.getCoverageNotes().endsWith("SME to confirm"));
        assertTrue(a.getCoverageNotes() == null || a.getCoverageNotes().isBlank());
    }
}
