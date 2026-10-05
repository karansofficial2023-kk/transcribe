package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LanguageSupportTest {
    private static final String TAMIL = "ஒளிச்சேர்க்கை என்பது தாவரங்கள் உணவு தயாரிக்கும் செயல்முறை ஆகும்.";
    private static final String TELUGU = "కిరణజన్య సంయోగక్రియ అనేది మొక్కలు ఆహారం తయారు చేసే ప్రక్రియ.";
    private static final String HINDI = "प्रकाश संश्लेषण वह प्रक्रिया है जिसमें पौधे भोजन बनाते हैं।";

    @Test
    void detectsEachScript() {
        assertEquals("ta", LanguageSupport.detect(TAMIL).code());
        assertEquals("te", LanguageSupport.detect(TELUGU).code());
        assertEquals("hi", LanguageSupport.detect(HINDI).code());
        assertEquals("en", LanguageSupport.detect("Plants make food by photosynthesis.").code());
        assertEquals("ml", LanguageSupport.detect("പ്രകാശസംശ്ലേഷണം സസ്യങ്ങൾ ഭക്ഷണം നിർമ്മിക്കുന്ന പ്രക്രിയയാണ്.").code());
        assertEquals("kn", LanguageSupport.detect("ದ್ಯುತಿಸಂಶ್ಲೇಷಣೆ ಸಸ್ಯಗಳು ಆಹಾರ ತಯಾರಿಸುವ ಪ್ರಕ್ರಿಯೆ.").code());
        assertEquals("ur", LanguageSupport.detect("پودے روشنی سے اپنی خوراک بناتے ہیں۔").code());
    }

    @Test
    void mixedTextTakesTheDominantScriptAndIgnoresDigitsAndFormulas() {
        assertEquals("ta", LanguageSupport.detect(TAMIL + " CO2 + H2O -> C6H12O6 6 12 6").code());
        assertEquals("te", LanguageSupport.detect("మొక్కలు photosynthesis ద్వారా ఆహారం తయారు చేస్తాయి").code());
    }

    @Test
    void asrHintResolvesShareddevanagari() {
        assertEquals("mr", LanguageSupport.detect(HINDI, "mr").code());
        assertEquals("hi", LanguageSupport.detect(HINDI, "hi").code());
        assertEquals("hi", LanguageSupport.detect(HINDI, "ta").code());        // a hint for another script is ignored
    }

    @Test
    void scriptShareMeasuresTranslationOutput() {
        LanguageSupport.Language tamil = LanguageSupport.byCode("ta").orElseThrow();
        assertTrue(LanguageSupport.scriptShare(TAMIL, tamil) > 0.95);
        assertTrue(LanguageSupport.scriptShare("Plants make food", tamil) < 0.05);
        assertEquals(0.0, LanguageSupport.scriptShare("", tamil));
    }

    @Test
    void lookupAcceptsCodesAndNames() {
        assertEquals("te", LanguageSupport.byCode("te-IN").orElseThrow().code());
        assertEquals("ta", LanguageSupport.byNameOrCode("Tamil").orElseThrow().code());
        assertEquals("ta", LanguageSupport.byNameOrCode("தமிழ்").orElseThrow().code());
        assertFalse(LanguageSupport.byCode("xx").isPresent());
        assertTrue(LanguageSupport.byCode("ur").orElseThrow().rtl());
    }

    @Test
    void languagePackageComesFromTheSameTable() {
        StoryboardContract.LanguagePackage tamil = StoryboardContract.LanguagePackage.forWhisperCode("ta");
        assertEquals("ta-IN", tamil.bcp47());
        assertEquals("Taml", tamil.script());
        assertEquals("ta-IN-PallaviNeural", tamil.voicePreference());
        assertEquals("Telu", StoryboardContract.LanguagePackage.forWhisperCode("te").script());
        assertEquals("rtl", StoryboardContract.LanguagePackage.forWhisperCode("ur").direction());
        assertEquals("en-IN", StoryboardContract.LanguagePackage.forWhisperCode("en").bcp47());
        assertEquals("fr", StoryboardContract.LanguagePackage.forWhisperCode("fr").bcp47());
    }

    @Test
    void writeInstructionNamesTheLanguage() {
        String instruction = LanguageSupport.writeInstruction(LanguageSupport.byCode("te").orElseThrow());
        assertTrue(instruction.contains("Telugu") && instruction.contains("తెలుగు"));
        assertTrue(LanguageSupport.writeInstruction(LanguageSupport.byCode("en").orElseThrow()).startsWith("Write every storyboard field in English"));
    }
}
