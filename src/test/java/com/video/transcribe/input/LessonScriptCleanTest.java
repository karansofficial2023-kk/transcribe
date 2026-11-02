package com.video.transcribe.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Cleaning an AI-written lesson script must remove markdown, never the arithmetic. */
class LessonScriptCleanTest {

    @Test
    void markdownEmphasisIsRemoved() {
        assertEquals("This is important and that is key.", LessonScriptWriter.clean("This is **important** and that is *key*."));
    }

    @Test
    void multiplicationSignsAreKeptAndWrittenAsTimes() {
        String cleaned = LessonScriptWriter.clean("The 10th term is a_10 = 10 + (10 - 1) * 5 = 55 and the sum is 5 * 65 = 325.");
        assertTrue(cleaned.contains("(10 - 1) × 5"), cleaned);
        assertTrue(cleaned.contains("5 × 65 = 325"), cleaned);
        assertFalse(cleaned.contains("*"), cleaned);
    }

    @Test
    void twoMultiplicationsOnOneLineAreNotTreatedAsEmphasis() {
        // the old rule paired the two asterisks and glued the numbers together ("2*10" became "210")
        String cleaned = LessonScriptWriter.clean("S_10 = 10/2 [2*10 + (10 - 1)*5] = 5 * 65");
        assertTrue(cleaned.contains("2 × 10"), cleaned);
        assertTrue(cleaned.contains("(10 - 1) × 5"), cleaned);
        assertTrue(cleaned.contains("5 × 65"), cleaned);
    }

    @Test
    void bulletsAndHeadingsStillGo() {
        assertEquals("Acids turn litmus red.", LessonScriptWriter.clean("# Title\n* Acids turn litmus red."));
    }
}
