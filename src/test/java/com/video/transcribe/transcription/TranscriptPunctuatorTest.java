package com.video.transcribe.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TranscriptPunctuatorTest {
    @Test
    void unpunctuatedTextHasLowTerminatorDensity() {
        String run = "hello everyone join anita on an electrifying journey as she embarks on a mission to electroplate an antique copper keychain";
        assertTrue(TranscriptPunctuator.density(run) < 3.0);
        assertTrue(TranscriptPunctuator.density("Hello everyone. Join Anita on a journey. She embarks on a mission. Let us begin.") > 15.0);
    }

    @Test
    void devanagariDandaCountsAsASentenceEnd() {
        assertTrue(TranscriptPunctuator.density("प्रकाश संश्लेषण एक प्रक्रिया है। इसमें पौधे भोजन बनाते हैं।") > 15.0);
    }

    @Test
    void wordComparisonIgnoresPunctuationAndCase() {
        assertEquals(TranscriptPunctuator.words("Hello, everyone! Join Anita."), TranscriptPunctuator.words("hello everyone join anita"));
    }
}
