package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Content may only be added to a lesson when real reference text was supplied; the lesson's own files prove nothing. */
class MaterialsEvidenceTest {
    @Test
    void theLessonsOwnVideoAndItsGeneratedFilesAreNotEvidence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Photosynthesis_Tamil.mp4"), "video");
        Files.writeString(dir.resolve("Photosynthesis_Tamil_transcript.json"), "{\"text\":\"தமிழ்\"}");
        Files.writeString(dir.resolve("Photosynthesis_Tamil_paraphrased.txt"), "paraphrase");
        Files.writeString(dir.resolve("Photosynthesis_Tamil_curriculum_enriched.txt"), "invented addition");
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(dir.toString(), "Photosynthesis_Tamil");
        assertFalse(materials.hasTextEvidence(), materials.promptContext());
        assertTrue(materials.isEmpty(), materials.promptContext());
    }

    @Test
    void aSuppliedReferenceDocumentIsEvidence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Photosynthesis_Tamil.mp4"), "video");
        Files.writeString(dir.resolve("Photosynthesis_Tamil_reference_notes.txt"), "Chlorophyll absorbs light in the chloroplast.");
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(dir.toString(), "Photosynthesis_Tamil");
        assertTrue(materials.hasTextEvidence(), materials.promptContext());
        assertTrue(materials.promptContext().contains("Chlorophyll"));
    }

    @Test
    void anImageAloneIsACandidateButNotPermissionToAddFacts(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Photosynthesis_Tamil_leaf.png"), "png");
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(dir.toString(), "Photosynthesis_Tamil");
        assertFalse(materials.approvedAssets().isEmpty());
        assertFalse(materials.hasTextEvidence());
    }

    @Test
    void pipelineOutputsAreRecognisedButATeachersOwnFilesAreNot() {
        for (String name : new String[] {"x_transcript.txt", "x_paraphrased.json", "x_manifest.json", "x_contract.json", "x_audit.json",
            "x_grounding.json", "x_glossary_candidates.json", "x_curriculum_enriched.txt", "x_ta_translation_review.json", "x_te_contract.json"}) {
            assertTrue(StoryboardProjectMaterialsLoader.isPipelineOutput(Path.of(name), "x"), name);
        }
        assertFalse(StoryboardProjectMaterialsLoader.isPipelineOutput(Path.of("teacher_notes.txt"), "x"));
        assertFalse(StoryboardProjectMaterialsLoader.isPipelineOutput(Path.of("narration_transcript.txt"), "x"));
        assertFalse(StoryboardProjectMaterialsLoader.isPipelineOutput(Path.of("y_transcript.txt"), "x"));
    }

    @Test
    void aLessonDoesNotPullInAnotherLessonsFilesWhoseNameStartsTheSame(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Lesson 10 teacher correction.md"), "Belongs to lesson ten.");
        Files.writeString(dir.resolve("Lesson 1 teacher correction.md"), "Belongs to lesson one.");
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(dir.toString(), "Lesson 1");
        assertTrue(materials.promptContext().contains("lesson one"), materials.promptContext());
        assertFalse(materials.promptContext().contains("lesson ten"), materials.promptContext());
    }

    @Test
    void wholeWordMatchingWorksInAnyScript() {
        assertTrue(StoryboardProjectMaterialsLoader.containsWords("D:/Input/ஒளிச்சேர்க்கை_notes.txt", "ஒளிச்சேர்க்கை"));
        assertFalse(StoryboardProjectMaterialsLoader.containsWords("Lesson 10 notes.txt", "Lesson 1"));
        assertTrue(StoryboardProjectMaterialsLoader.containsWords("C:/x/Photosynthesis_Tamil.mp4", "Photosynthesis_Tamil"));
    }

    @Test
    void glossariesAreNotEvidence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("glossary.json"), "{\"misheard\": \"correct\"}");
        Files.writeString(dir.resolve("Lesson_glossary.json"), "{\"misheard\": \"correct\"}");
        Files.writeString(dir.resolve("reference_notes.txt"), "Real subject text.");
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(dir.toString(), "Lesson");
        assertTrue(materials.promptContext().contains("Real subject text."));
        assertFalse(materials.promptContext().contains("misheard"), materials.promptContext());
    }
}
