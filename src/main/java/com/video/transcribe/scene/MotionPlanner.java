package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which shots get a moving picture. The video generator animates the approved still of a marked shot with LTX
 * image-to-video (about 4 s at 1080p) and shows the still unchanged when the clip fails its checks, so marking a shot is
 * always safe, never a requirement.
 *
 * Rules, independent of the lesson's subject:
 *  - the closing shot gets motion (a living last impression); the opening title keeps a plain themed background and never moves;
 *  - every shot the director already planned as a short natural-motion clip gets motion;
 *  - a shot with labels, formulas, steps or a supplied image never moves: labels need a picture that stands still,
 *    formulas and cards are drawn deterministically, and supplied images are evidence.
 * At most {@link #MAX_CLIPS} shots per lesson move, the closing shot first (a clip costs about a minute and a half).
 */
public final class MotionPlanner {
    public static final int MAX_CLIPS = 6;

    private MotionPlanner() {
    }

    /** Idempotent: clears and recomputes the flags. */
    public static int plan(List<Scene> scenes) {
        if (scenes == null) return 0;
        List<SceneSegment> all = new ArrayList<>();
        for (Scene scene : scenes) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                segment.setAnimate(false);
                all.add(segment);
            }
        }
        if (all.isEmpty()) return 0;

        List<SceneSegment> chosen = new ArrayList<>();
        SceneSegment last = all.get(all.size() - 1);
        if (all.size() > 1 && canMove(last)) chosen.add(last);
        for (SceneSegment segment : all) {
            if (chosen.size() >= MAX_CLIPS) break;
            if (!chosen.contains(segment) && canMove(segment)
                    && ("short_motion_clip".equals(segment.getVisualType()) || "video_broll".equals(segment.getTemplate()))) {
                chosen.add(segment);
            }
        }
        chosen.forEach(segment -> segment.setAnimate(true));
        return chosen.size();
    }

    static boolean canMove(SceneSegment segment) {
        if (segment == null) return false;
        if (notEmpty(segment.getLabels()) || notEmpty(segment.getLabelPlacements()) || notEmpty(segment.getFormulaLines())
                || notEmpty(segment.getSteps()) || notEmpty(segment.getColumns())) return false;
        if (segment.getAssetPath() != null && !segment.getAssetPath().isBlank()) return false;     // a supplied image is evidence
        if ("manim".equals(segment.getTool())) return false;
        String template = segment.getTemplate();
        String type = segment.getVisualType();
        boolean pictureTemplate = "photo".equals(template) || "video_broll".equals(template);       // a title card is plain colour: nothing to animate
        boolean pictureType = "realistic_image".equals(type) || "short_motion_clip".equals(type);
        return pictureTemplate && pictureType;
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }
}
