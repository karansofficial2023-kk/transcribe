package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

/** Re-plan and regeneration tools build an exporter without a language; the contract must still carry the lesson's own language. */
class ExporterLanguageTest {
    private static StoryboardDocument lesson(String sentence) {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setSentence(sentence);
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSegments(List.of(segment));
        StoryboardDocument document = new StoryboardDocument();
        document.setScenes(List.of(scene));
        return document;
    }

    @Test
    void withoutAnExplicitLanguageItIsReadFromTheNarration() {
        StoryboardDocxExporter exporter = new StoryboardDocxExporter("ltx");
        assertEquals("ta-IN", exporter.languageFor(lesson("ஒளிச்சேர்க்கை என்பது தாவரங்கள் உணவு தயாரிக்கும் செயல்முறை.")).bcp47());
        assertEquals("te-IN", exporter.languageFor(lesson("కిరణజన్య సంయోగక్రియ అనేది మొక్కలు ఆహారం తయారు చేసే ప్రక్రియ.")).bcp47());
        assertEquals("en-IN", exporter.languageFor(lesson("Plants make food by photosynthesis.")).bcp47());
    }

    @Test
    void anExplicitLanguageAlwaysWins() {
        StoryboardDocxExporter exporter = new StoryboardDocxExporter("ltx")
            .withLanguage(StoryboardContract.LanguagePackage.forWhisperCode("mr"));
        assertEquals("mr-IN", exporter.languageFor(lesson("पौधे सूर्य के प्रकाश से भोजन बनाते हैं।")).bcp47());
    }
}
