package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

class TopicTitleTest {

    @Test
    void aTopicLessonIsTitledByItsTopicNotItsFileName() {
        SceneSegment title = new SceneSegment();
        title.setTemplate("title_card");
        title.setHeading("Ohms Law Test");
        SceneSegment shot = new SceneSegment();
        shot.setTemplate("photo");
        shot.setHeading("Ohms Law Test");                       // only the title card takes the topic
        Scene scene = new Scene();
        scene.setSegments(new ArrayList<>(List.of(title, shot)));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Ohms Law Test");
        doc.setScenes(new ArrayList<>(List.of(scene)));

        VideoParaphrasePipeline.applyTopicTitle(doc, "Ohm's Law and Resistance");

        assertEquals("Ohm's Law and Resistance", doc.getTitle());
        assertEquals("Ohm's Law and Resistance", title.getHeading());
        assertEquals("Ohms Law Test", shot.getHeading());
    }
}
