package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StoryboardTeacherPassTest {

    private static SceneSegment shot(int n, String heading, String sentence) {
        SceneSegment s = new SceneSegment();
        s.setSegmentNumber(n);
        s.setHeading(heading);
        s.setSentence(sentence);
        s.setTemplate("photo");
        return s;
    }

    private static StoryboardDocument board(String sceneTitle, SceneSegment... shots) {
        Scene scene = new Scene();
        scene.setSceneNumber(7);
        scene.setSceneTitle(sceneTitle);
        StringBuilder narration = new StringBuilder();
        for (SceneSegment s : shots) narration.append(s.getSentence()).append(' ');
        scene.setNarration(narration.toString().trim());
        scene.setSegments(new ArrayList<>(List.of(shots)));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Applications of Kohlrausch's Law");
        doc.setScenes(new ArrayList<>(List.of(scene)));
        return doc;
    }

    @Test
    void anOffTopicSayingIsRemovedButTeachingIsKept() {
        StoryboardDocument doc = board("Kohlrausch's Law and Molar Conductivity",
            shot(1, "Metaphor of Doors", "This video effectively demonstrates the old saying that when one door closes, another opens."),
            shot(2, "Kohlrausch's Law", "Kohlrausch's law helps determine the limiting molar conductivity of weak electrolytes."),
            shot(3, "Other Applications", "Do you think there are other applications for Kohlrausch's law?"),
            shot(4, "Summary", "So the limiting molar conductivity of acetic acid comes from its ions."));
        StoryboardTeacherPass.Report report = StoryboardTeacherPass.apply(doc, (system, user) -> "{\"off_topic\": [\"7.1\"]}");
        List<SceneSegment> left = doc.getScenes().get(0).getSegments();
        assertEquals(3, left.size());
        assertTrue(left.get(0).getSentence().startsWith("Kohlrausch's law helps"));
        assertEquals(1, left.get(0).getSegmentNumber());                   // renumbered
        assertTrue(report.removed().get(0).startsWith("7.1"));
        assertTrue(!doc.getScenes().get(0).getNarration().contains("door closes"));
    }

    @Test
    void anOverEagerModelRemovesNothing() {
        StoryboardDocument doc = board("Topic", shot(1, "A", "One idea about ions."), shot(2, "B", "Another idea about ions."),
            shot(3, "C", "A third idea about ions."), shot(4, "D", "A fourth idea about ions."));
        StoryboardTeacherPass.apply(doc, (s, u) -> "{\"off_topic\": [\"7.1\", \"7.2\", \"7.3\"]}");
        assertEquals(4, doc.getScenes().get(0).getSegments().size());
    }

    @Test
    void onlyEvidentlyWrongHeadingsAreReplaced() {
        StoryboardDocument doc = board("This simplifies to A0 divided by 1.1.",
            shot(1, "This simplifies to A0 divided by 1.1.", "This simplifies to A0 divided by 1.1."),
            shot(2, "Resistance After Stretching a Wire", "Substituting the values, this becomes Rho times 1.1 L0 divided by A0 over 1.1."),
            shot(3, "Weak acids and bases are classified", "Weak acids and bases are classified as weak electrolytes due to their low molar conductivities."),
            shot(4, "Weak Electrolytes", "Weak acids and bases are weak electrolytes."));
        StoryboardTeacherPass.apply(doc, null);
        List<SceneSegment> shots = doc.getScenes().get(0).getSegments();
        assertEquals("Resistance After Stretching a Wire", shots.get(1).getHeading());                // topical heading stays
        assertEquals("Weak Electrolytes", shots.get(3).getHeading());
        assertTrue(!shots.get(0).getHeading().endsWith("."), shots.get(0).getHeading());               // a sentence is not a title
        assertTrue(!shots.get(2).getHeading().equals("Weak acids and bases are classified"));         // nor a cut sentence
        assertEquals("Resistance After Stretching a Wire", doc.getScenes().get(0).getSceneTitle());   // scene title from a good heading
    }
}
