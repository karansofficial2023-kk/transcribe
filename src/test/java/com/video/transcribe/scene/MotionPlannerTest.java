package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MotionPlannerTest {

    private static SceneSegment shot(int number, String template, String visualType, List<String> labels) {
        SceneSegment s = new SceneSegment();
        s.setSegmentNumber(number);
        s.setTemplate(template);
        s.setVisualType(visualType);
        s.setLabels(labels);
        return s;
    }

    private static List<Scene> lesson(SceneSegment... segments) {
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSegments(new ArrayList<>(List.of(segments)));
        return new ArrayList<>(List.of(scene));
    }

    @Test
    void closingPictureMoves_titleLabelledAndCardShotsStay() {
        SceneSegment title = shot(1, "title_card", "title_card", List.of());
        SceneSegment labelled = shot(2, "labeled_image", "realistic_labeled_image", List.of("Anther"));
        SceneSegment cards = shot(3, "process", "process_steps", List.of());
        SceneSegment closing = shot(4, "photo", "realistic_image", List.of());
        MotionPlanner.plan(lesson(title, labelled, cards, closing));
        assertFalse(title.isAnimate());                 // a title card is plain colour
        assertFalse(labelled.isAnimate());
        assertFalse(cards.isAnimate());
        assertTrue(closing.isAnimate());
    }

    @Test
    void aClosingShotWithLabelsStaysStillAndPlannedMotionClipsMove() {
        SceneSegment title = shot(1, "title_card", "title_card", List.of());
        SceneSegment clip = shot(2, "video_broll", "short_motion_clip", List.of());
        SceneSegment closing = shot(3, "labeled_image", "realistic_labeled_image", List.of("Root"));
        MotionPlanner.plan(lesson(title, clip, closing));
        assertTrue(clip.isAnimate());
        assertFalse(closing.isAnimate());
    }

    @Test
    void suppliedImagesAreEvidenceAndNeverMove() {
        SceneSegment title = shot(1, "title_card", "title_card", List.of());
        SceneSegment closing = shot(2, "photo", "realistic_image", List.of());
        closing.setAssetPath("C:/evidence/photo.png");
        MotionPlanner.plan(lesson(title, closing));
        assertFalse(closing.isAnimate());
    }

    @Test
    void atMostSixClipsAndPlanningIsRepeatable() {
        List<SceneSegment> rows = new ArrayList<>();
        rows.add(shot(1, "title_card", "title_card", List.of()));
        for (int i = 2; i <= 12; i++) rows.add(shot(i, "video_broll", "short_motion_clip", List.of()));
        rows.add(shot(13, "photo", "realistic_image", List.of()));
        List<Scene> scenes = lesson(rows.toArray(new SceneSegment[0]));
        assertEquals(MotionPlanner.MAX_CLIPS, MotionPlanner.plan(scenes));
        assertEquals(MotionPlanner.MAX_CLIPS, MotionPlanner.plan(scenes));
        assertFalse(rows.get(0).isAnimate());
        assertTrue(rows.get(12).isAnimate());          // the closing shot keeps its place even when many clips were planned
    }
}
