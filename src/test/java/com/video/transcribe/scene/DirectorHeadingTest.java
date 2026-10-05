package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirectorHeadingTest {
    private static final String EN = "Oxygen is released from the leaves during this process, which is why plants matter so much.";
    private static final String TA = "இந்த செயல்முறையின் போது, ஆக்சிஜன் வெளியேற்றப்படுகிறது.";

    @Test
    void shortTopicTitlesInTheNarrationLanguageAreAccepted() {
        assertTrue(VisualDirector.validHeading("Oxygen released by leaves", EN));
        assertTrue(VisualDirector.validHeading("ஆக்சிஜன் வெளியேற்றம்", TA));
    }

    @Test
    void wrongLanguageCopiedOrDanglingHeadingsAreRejected() {
        assertFalse(VisualDirector.validHeading("Oxygen release", TA), "English heading on a Tamil lesson");
        assertFalse(VisualDirector.validHeading("Oxygen is released from the leaves", EN), "copy of the sentence opening");
        assertFalse(VisualDirector.validHeading("Oxygen and the leaves,", EN), "ends in punctuation");
        assertFalse(VisualDirector.validHeading("", EN));
        assertFalse(VisualDirector.validHeading(null, EN));
        assertFalse(VisualDirector.validHeading("A very long title that goes on and on well beyond any sensible limit for a card", EN));
    }
}
