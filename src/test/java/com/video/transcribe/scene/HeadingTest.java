package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeadingTest {
    private final SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);

    @Test
    void shortSentencesAreKeptWithoutTrailingPunctuation() {
        assertEquals("The anode is positive", generator.buildHeading("The anode is positive"));
        assertEquals("Let's begin", generator.buildHeading("Let's begin:"));
    }

    @Test
    void longSentencesBreakAtAClauseNotMidPhrase() {
        String heading = generator.buildHeading(
            "Let's drag the labels into their respective boxes: the terminal connected to the battery's positive terminal is the anode.");
        assertEquals("Let's drag the labels into their respective boxes", heading);
    }

    @Test
    void neverEndsOnADanglingWord() {
        String heading = generator.buildHeading("Anita embarks on an exciting journey to electroplate an old copper keychain with silver");
        assertFalse(heading.endsWith(" to"), heading);
        assertTrue(heading.length() <= 54, heading);
        assertTrue(heading.startsWith("Anita embarks"), heading);
    }

    @Test
    void scriptsOtherThanLatinAreCutOnWordBoundaries() {
        String heading = generator.buildHeading("அனிதா விவாண்டுவுக்கு ஒரு சிறப்பான பிறந்தநாள் பரிசாக பழமையான தாமிர சாவிக்கொத்தை வெள்ளி முலாம் பூச முடிவு செய்தாள்");
        assertTrue(heading.length() <= 54, heading);
        assertFalse(heading.endsWith(" "), heading);
    }
}
