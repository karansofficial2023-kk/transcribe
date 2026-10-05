package com.video.transcribe.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlossaryTest {
    @Test
    void appliesWholeWordCorrectionsInIndicScript(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("glossary.json"), "{\"पोधे\": \"पौधे\", \"संशलेशन\": \"संश्लेषण\"}");
        Glossary glossary = Glossary.load("zz", dir.toString(), "lesson");
        assertEquals(2, glossary.size());
        assertEquals("प्रकाश संश्लेषण में पौधे भोजन बनाते हैं", glossary.apply("प्रकाश संशलेशन में पोधे भोजन बनाते हैं"));
        assertEquals("पोधेवाला", glossary.apply("पोधेवाला"));          // not a whole word: untouched
    }

    @Test
    void lessonGlossaryOverridesSharedOne(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("glossary.json"), "{\"v\": \"voltage\"}");
        Files.writeString(dir.resolve("ohm_glossary.json"), "{\"v\": \"V\"}");
        assertEquals("V equals I R", Glossary.load("en", dir.toString(), "ohm").apply("v equals I R"));
    }

    @Test
    void hotwordsAreTheCorrectSpellings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("glossary.json"), "{\"anod\": \"anode\", \"catod\": \"cathode\", \"anod2\": \"anode\"}");
        assertEquals(java.util.List.of("anode", "cathode"), Glossary.load("zz", dir.toString(), "x").hotwords());
    }

    @Test
    void emptyGlossaryLeavesTextUnchanged() {
        assertTrue(Glossary.load("zz", "", "x").size() == 0);
        assertEquals("same text", new Glossary().apply("same text"));
    }
}
