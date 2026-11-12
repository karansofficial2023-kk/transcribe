package com.video.transcribe;

import com.video.transcribe.scene.StoryboardTranslator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HindiNumberTranslationTest {
    @Test
    void acceptsSpokenHindiNumberForSourceDigit() {
        var hindi = LanguageSupport.byCode("hi").orElseThrow();
        assertNull(StoryboardTranslator.problem("It shares all 4 electrons with 4 atoms.",
            "यह चार इलेक्ट्रॉन चार परमाणुओं के साथ साझा करता है।", hindi));
        assertEquals("number 4 is missing", StoryboardTranslator.problem(
            "It shares all 4 electrons with 4 atoms.", "यह तीन इलेक्ट्रॉन साझा करता है।", hindi));
    }
}
