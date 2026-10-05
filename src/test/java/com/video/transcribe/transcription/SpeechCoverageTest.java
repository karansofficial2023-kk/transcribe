package com.video.transcribe.transcription;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeechCoverageTest {

    @Test
    void musicOnlyNineMinuteVideoWithOneHallucinatedLineIsRejected() {
        String problem = SpeechCoverage.problem("Thank you for watching.", 537.26);
        assertNotNull(problem);
        assertTrue(problem.contains("no narration"), problem);
    }

    @Test
    void normalNarrationPasses() {
        String text = "word ".repeat(700).trim();             // ~78 words/min over 9 minutes
        assertNull(SpeechCoverage.problem(text, 537.26));
    }

    @Test
    void sparseButRealNarrationPasses() {
        String text = "word ".repeat(120).trim();             // 24 words/min over 5 minutes
        assertNull(SpeechCoverage.problem(text, 300));
    }

    @Test
    void shortClipsAreOnlyRejectedWhenEmpty() {
        assertNull(SpeechCoverage.problem("Welcome to the lesson.", 12));
        assertNotNull(SpeechCoverage.problem("   ", 12));
        assertNotNull(SpeechCoverage.problem((String) null, 12));
    }

    @Test
    void emptyTranscriptOfLongVideoIsRejected() {
        assertNotNull(SpeechCoverage.problem("", 600));
    }
}
