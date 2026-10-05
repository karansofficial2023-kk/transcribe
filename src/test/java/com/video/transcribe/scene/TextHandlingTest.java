package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.video.transcribe.transcription.Glossary;

class TextHandlingTest {
    private final SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);

    @Test
    void subtitleCleanupKeepsMultiplicationSignsAndSnakeCase() {
        assertEquals("2 * 3 = 6 and 4 * 5 = 20", generator.cleanSubtitleText("2 * 3 = 6 and 4 * 5 = 20"));
        assertEquals("Use x_1 and y_2 here", generator.cleanSubtitleText("Use x_1 and y_2 here"));
    }

    @Test
    void subtitleCleanupStillRemovesMarkdownEmphasis() {
        assertEquals("a very important point", generator.cleanSubtitleText("a *very* important point"));
        assertEquals("a very important point", generator.cleanSubtitleText("a **very** important point"));
        assertEquals("an important point", generator.cleanSubtitleText("an _important_ point"));
    }

    @Test
    void aTamilOrHindiFileNameStillGivesAFilenameTopic() {
        assertFalse(generator.titleFromBaseName("ஒளிச்சேர்க்கை_பாடம்").isBlank());
        assertFalse(generator.titleFromBaseName("प्रकाश_संश्लेषण").isBlank());
    }

    @Test
    void glossaryEntriesWithoutACorrectionAreIgnoredInsteadOfCrashing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("glossary.json"), "{\"anod\": null, \"catod\": \"cathode\", \"\": \"x\"}");
        Glossary glossary = Glossary.load("zz", dir.toString(), "lesson");
        assertEquals(1, glossary.size());
        assertEquals("the cathode", glossary.apply("the catod"));
        assertEquals("anod stays", glossary.apply("anod stays"));
        assertTrue(glossary.hotwords().contains("cathode"));
    }

    @Test
    void urduAndDevanagariSentenceEndsSplitSentences() {
        assertEquals(2, generator.splitSentences("پودے روشنی سے خوراک بناتے ہیں۔ یہ عمل پتوں میں ہوتا ہے۔").size());
        assertEquals(2, generator.splitSentences("क्या पौधे भोजन बनाते हैं? हाँ, वे बनाते हैं।").size());
    }
}
