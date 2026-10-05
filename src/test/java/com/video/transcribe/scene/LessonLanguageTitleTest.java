package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.video.transcribe.LanguageSupport;

class LessonLanguageTitleTest {
    private final SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
    private static final String TELUGU = "కిరణజన్య సంయోగక్రియ అనేది ఆకుపచ్చ మొక్కలు సూర్యకిరణాలను ఉపయోగించి ఆహారాన్ని తయారు చేసుకునే ప్రక్రియ. "
        + "ఈ ప్రక్రియలో మొక్కలు గాలి నుండి కార్బన్ డయాక్సైడ్ ను తీసుకుంటాయి.";

    @Test
    void anEnglishFilenameTitleOnATeluguLessonBecomesTelugu() {
        String title = generator.lessonLanguageTitle("Photosynthesis Telugu", TELUGU);
        assertTrue(LanguageSupport.scriptShare(title, LanguageSupport.byCode("te").orElseThrow()) >= 0.7, title);
        assertTrue(title.startsWith("కిరణజన్య"), title);
    }

    @Test
    void aTitleAlreadyInTheLessonLanguageIsKept() {
        assertEquals("కిరణజన్య సంయోగక్రియ", generator.lessonLanguageTitle("కిరణజన్య సంయోగక్రియ", TELUGU));
    }

    @Test
    void englishLessonsAreNeverChanged() {
        assertEquals("All About Electroplating",
            generator.lessonLanguageTitle("All About Electroplating", "Electroplating coats an object with a thin layer of metal."));
    }
}
