package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.video.transcribe.model.TranscriptData;

class TranscriptReuseTest {
    @TempDir
    Path dir;

    private Path json(String body) throws Exception {
        return Files.writeString(dir.resolve("t.json"), body);
    }

    @Test
    void punctuatedTextFromTheFirstJobReplacesTheRawRecogniserText() throws Exception {
        Path txt = Files.writeString(dir.resolve("t.txt"), "Ohm's law. V equals IR.");
        TranscriptData reused = VideoParaphrasePipeline.reusableTranscript(
            json("{\"text\":\"ohms law v equals ir\",\"language\":\"en\"}"), txt, "en");
        assertNotNull(reused);
        assertEquals("Ohm's law. V equals IR.", reused.getFullText());
    }

    @Test
    void rawTextIsKeptWhenNoPunctuatedFileExists() throws Exception {
        TranscriptData reused = VideoParaphrasePipeline.reusableTranscript(
            json("{\"text\":\"raw words\",\"language\":\"en\"}"), dir.resolve("missing.txt"), null);
        assertEquals("raw words", reused.getFullText());
    }

    @Test
    void aTranscriptInAnotherLanguageIsNotReused() throws Exception {
        assertNull(VideoParaphrasePipeline.reusableTranscript(
            json("{\"text\":\"வணக்கம்\",\"language\":\"ta\"}"), dir.resolve("none.txt"), "te"));
    }

    @Test
    void emptyOrBrokenTranscriptsAreNotReused() throws Exception {
        assertNull(VideoParaphrasePipeline.reusableTranscript(json("{\"text\":\"  \",\"language\":\"en\"}"), dir.resolve("n.txt"), "en"));
        assertNull(VideoParaphrasePipeline.reusableTranscript(json("null"), dir.resolve("n.txt"), "en"));
    }
}
