package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A subtitle that lists a sequence ends with a mathematical ellipsis; only cut-off text counts as an incomplete subtitle. */
class SubtitleEllipsisTest {

    @Test
    void mathematicalEllipsisIsNotTruncation() {
        assertFalse(StoryboardQualityGate.isTruncated("In the sequence 2, 5, 8, 11, ..., the common difference is 3."));
        assertFalse(StoryboardQualityGate.isTruncated("The savings are $10, $15, $20, ... each month."));
        assertFalse(StoryboardQualityGate.isTruncated("1, 2, ..., n"));
        assertFalse(StoryboardQualityGate.isTruncated("a, a + d, a + 2d, ..."));
        assertFalse(StoryboardQualityGate.isTruncated("A number line with dots marking 3, 6, 9, 12, … and an arrow to the right."));
    }

    @Test
    void unicodeEllipsisAfterTextIsStillTruncation() {
        assertTrue(StoryboardQualityGate.isTruncated("The tube grows towards the ovule and…"));
    }

    @Test
    void cutOffTextIsStillRejected() {
        assertTrue(StoryboardQualityGate.isTruncated("The pollen tube grows down the style and..."));
        assertTrue(StoryboardQualityGate.isTruncated("..."));
        assertTrue(StoryboardQualityGate.isTruncated("An acid is a substance that gives hydrogen ions in water, ..."));
    }

    @Test
    void plainTextIsFine() {
        assertFalse(StoryboardQualityGate.isTruncated("An acid turns blue litmus red."));
        assertFalse(StoryboardQualityGate.isTruncated(null));
    }
}
