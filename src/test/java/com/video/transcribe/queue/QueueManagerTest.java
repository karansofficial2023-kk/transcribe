package com.video.transcribe.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueueManagerTest {
    @Test
    void videoExtensionsAreRecognisedInAnyCase() {
        for (String name : new String[] {"lesson.mp4", "LESSON.MP4", "Lesson.Mov", "ஒளிச்சேர்க்கை.mkv", "a b.webm", "x.FLV"}) {
            assertTrue(QueueManager.isVideoFile(name), name);
        }
    }

    @Test
    void otherFilesAreNotVideos() {
        for (String name : new String[] {"notes.txt", "lesson.mp4.part", "mp4", "storyboard.docx", "", null}) {
            assertFalse(QueueManager.isVideoFile(name), String.valueOf(name));
        }
    }
}
