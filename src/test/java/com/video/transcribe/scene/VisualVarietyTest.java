package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class VisualVarietyTest {
    private final SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);

    @Test
    void sentencesSplitAtPhraseMarksInAnyScript() {
        assertEquals(List.of("కాంతి శక్తి సహాయంతో", "కార్బన్ డయాక్సైడ్ మరియు నీరు కలిసి గ్లూకోజ్ ఏర్పడుతుంది"),
            generator.deterministicTeachingSteps("కాంతి శక్తి సహాయంతో, కార్బన్ డయాక్సైడ్ మరియు నీరు కలిసి గ్లూకోజ్ ఏర్పడుతుంది."));
        assertEquals(2, generator.deterministicTeachingSteps("குளுக்கோஸ் தாவரத்திற்கு ஆற்றலை வழங்குகிறது, மீதமுள்ள குளுக்கோஸ் மாவுச்சத்தாக சேமிக்கப்படுகிறது.").size());
        assertEquals(2, generator.deterministicTeachingSteps("पौधे सूर्य के प्रकाश से भोजन बनाते हैं; यह प्रक्रिया पत्तियों में होती है।").size());
    }

    @Test
    void aSentenceWithoutPunctuationIsCutOnceNearTheMiddle() {
        List<String> steps = generator.deterministicTeachingSteps("ఈ ప్రక్రియలో మొక్కలు గాలి నుండి కార్బన్ డయాక్సైడ్‌ను నేల నుండి నీటిని తీసుకుంటాయి");
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).split(" ").length >= 3 && steps.get(1).split(" ").length >= 3, steps.toString());
    }

    @Test
    void tooShortToTeachInStepsGivesNone() {
        assertTrue(generator.deterministicTeachingSteps("Thank you").isEmpty());
        assertTrue(generator.deterministicTeachingSteps("").isEmpty());
        assertTrue(generator.deterministicTeachingSteps(null).isEmpty());
    }
}
