package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryboardProjectMaterialsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsOnlyTheNamedProjectAndOrdersTeacherCorrectionsFirst() throws Exception {
        Path lesson = Files.createDirectories(tempDir.resolve("Lesson One"));
        Files.writeString(lesson.resolve("teacher_corrections.md"), "Approved correction.");
        Files.writeString(lesson.resolve("narration_transcript.txt"), "Narration source.");
        Files.writeString(lesson.resolve("old_storyboard.txt"), "Generated feedback loop.");
        Files.write(lesson.resolve("reference.png"), new byte[] { 1, 2, 3 });
        Files.writeString(tempDir.resolve("unrelated_transcript.txt"), "Must not be loaded.");

        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(
            tempDir.resolve("{baseName}").toString(), "Lesson One");

        String context = materials.promptContext();
        assertTrue(context.indexOf("Approved correction.") < context.indexOf("Narration source."));
        assertTrue(!context.contains("Must not be loaded."));
        assertTrue(!context.contains("Generated feedback loop."));
        assertTrue(context.contains("USE asset_path ONLY IF ITS APPROVAL IS EXPLICIT"));
        assertEquals(1, materials.approvedAssets().size());
    }
}
