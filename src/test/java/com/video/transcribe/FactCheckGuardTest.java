package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FactCheckGuardTest {
    private static final String ORIGINAL = "A current of 6 volts is applied to the silver rod for 10 minutes, and 2.5 grams of silver are deposited.";

    @Test
    void aWordingFixThatKeepsEveryNumberIsAccepted() {
        assertNull(VideoParaphrasePipeline.factCheckProblem(ORIGINAL,
            "A current of 6 volts is passed through the silver rod for 10 minutes, and 2.5 grams of silver are deposited."));
    }

    @Test
    void aRewriteThatChangesOrDropsANumberIsRejected() {
        assertNotNull(VideoParaphrasePipeline.factCheckProblem(ORIGINAL,
            "A current of 12 volts is applied to the silver rod for 10 minutes, and 2.5 grams of silver are deposited."));
        assertNotNull(VideoParaphrasePipeline.factCheckProblem(ORIGINAL, "A current is applied to the silver rod and some silver is deposited on it over time."));
    }

    @Test
    void aRewriteThatRunsOnOrCollapsesIsRejected() {
        assertNotNull(VideoParaphrasePipeline.factCheckProblem(ORIGINAL, ORIGINAL + " " + ORIGINAL));
        assertNotNull(VideoParaphrasePipeline.factCheckProblem(ORIGINAL, "6 volts, 10 minutes, 2.5 grams."));
    }
}
