package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafePromptTest {

    @Test
    void sensitiveLessonStripsBodyWordsAndKeepsTheOrgans() {
        String prompt = "A photograph of a young woman, human body torso silhouette, uterus and ovaries glowing softly, dark blue background";
        String safe = VisualDirector.safePrompt(prompt, "Hormones in the female reproductive system. Puberty begins.");
        assertTrue(safe.contains("uterus and ovaries glowing"), safe);
        assertTrue(safe.contains("dark blue background"), safe);
        for (String banned : new String[] {"human body", "torso", "woman", "silhouette", "photograph"}) {
            assertFalse(safe.toLowerCase().contains(banned), banned + " in: " + safe);
        }
        assertFalse(safe.contains("  "), safe);
        assertFalse(safe.startsWith(","), safe);
    }

    @Test
    void otherLessonsAreUnchanged() {
        String prompt = "A bee on a flower, close-up, soft morning light";
        assertEquals(prompt, VisualDirector.safePrompt(prompt, "Pollination: a bee carries pollen from flower to flower."));
    }

    @Test
    void neverReturnsAnEmptyPrompt() {
        String prompt = "human body, torso, silhouette";
        assertEquals(prompt, VisualDirector.safePrompt(prompt, "Puberty and the human body"));
    }
}
