package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplacementCharacterTest {
    @Test
    void aLostApostropheInsideAnEnglishWordIsRepaired() {
        assertEquals("don't stop", SceneStoryboardGenerator.normalizeProductionText("don\uFFFDt stop"));
    }

    @Test
    void corruptionInsideAnIndicWordIsKeptSoTheQualityGateCanRejectIt() {
        String corrupted = "ப\uFFFDர்";
        assertTrue(SceneStoryboardGenerator.normalizeProductionText(corrupted).indexOf('\uFFFD') >= 0);
    }

    @Test
    void aStandaloneReplacementCharacterIsNotTurnedIntoAnApostrophe() {
        assertTrue(SceneStoryboardGenerator.normalizeProductionText("word \uFFFD word").indexOf('\uFFFD') >= 0);
    }
}
