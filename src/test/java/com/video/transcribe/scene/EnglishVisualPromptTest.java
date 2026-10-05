package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.video.transcribe.LanguageSupport;

/** Image and video models read English only: a Tamil or Telugu lesson must never put its narration into their prompts. */
class EnglishVisualPromptTest {
    private final SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);

    private static SceneSegment tamilShot() {
        SceneSegment segment = new SceneSegment();
        segment.setSentence("இலைகளில் உள்ள பச்சையம் சூரிய ஒளியை மாற்றி ஆற்றலாக கொண்டுவருகின்றன.");
        segment.setVisualAnimation("இலைகளின் மீது சூரிய ஒளி விழுகிறது.");
        segment.setVisualSubject("Viewer must see chlorophyll in the leaf reacting to sunlight.");
        Shot shot = new Shot();
        shot.setPrompt("A natural motion video of sunlight hitting leaves, designed for 8.0 seconds with seamless natural continuation");
        segment.setShot(shot);
        return segment;
    }

    @Test
    void thePlannedEnglishVideoPromptWinsAndItsTimingBoilerplateIsDropped() {
        assertEquals("A natural motion video of sunlight hitting leaves", generator.englishVisualBase(tamilShot()));
    }

    @Test
    void withoutAVideoPromptTheEnglishVisualSubjectIsUsed() {
        SceneSegment segment = tamilShot();
        segment.setShot(null);
        assertEquals("Viewer must see chlorophyll in the leaf reacting to sunlight", generator.englishVisualBase(segment));
    }

    @Test
    void neverReturnsNativeScriptText() {
        SceneSegment segment = tamilShot();
        segment.setShot(null);
        segment.setVisualSubject(null);
        String base = generator.englishVisualBase(segment);
        assertFalse(LanguageSupport.scriptShare(base, LanguageSupport.byCode("ta").orElseThrow()) > 0, base);
        assertTrue(base.startsWith("the subject described"), base);
    }

    @Test
    void englishLessonsStillUseTheirOwnSentence() {
        SceneSegment segment = new SceneSegment();
        segment.setSentence("Silver ions are attracted to the negative electrode.");
        assertEquals("Silver ions are attracted to the negative electrode", generator.englishVisualBase(segment));
    }
}
