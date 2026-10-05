package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StoryboardIdentityTest {

    @Test
    void calculusTitleMatchesItsMinimumLesson() {
        // real case: the exact-word check rejected this correct storyboard ("minimizing" vs "minimum", no word "calculus" spoken)
        String evidence = "A Simple Question Based on Minima of a Function f of x is equal to x plus 1 over x implies maxima or minima "
            + "the sum reaches its minimum value";
        assertTrue(VideoParaphrasePipeline.titleMatchesEvidence("Minimizing f(x) = x + 1/x Using Calculus", evidence));
    }

    @Test
    void unrelatedTopicIsStillRefused() {
        String evidence = "All About Electroplating the keychain is attached to the negative terminal silver nitrate solution";
        assertFalse(VideoParaphrasePipeline.titleMatchesEvidence("Photosynthesis in Green Plants", evidence));
        assertFalse(VideoParaphrasePipeline.titleMatchesEvidence("Bacterial Diseases Cholera and Typhoid", evidence));
    }

    @Test
    void titleWithoutSignificantWordsIsNotBlocked() {
        assertTrue(VideoParaphrasePipeline.titleMatchesEvidence("f(x) = x", "anything"));
    }
}
