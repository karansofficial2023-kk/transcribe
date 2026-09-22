package com.video.transcribe.scene;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Final invariant check before storyboard JSON or DOCX is written. */
public final class StoryboardQualityGate {
    private static final String NUMBER = "(0(?:\\.\\d+)?|1(?:\\.0+)?)";
    private static final Pattern NUMERIC_TARGET = Pattern.compile(
        "target=\\(\\s*" + NUMBER + "\\s*,\\s*" + NUMBER + "\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    private StoryboardQualityGate() {
    }

    public static void validate(StoryboardDocument storyboard) {
        if (storyboard == null || storyboard.getScenes() == null) {
            throw new IllegalStateException("Storyboard is empty");
        }
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                validateSegment(scene, segment);
            }
        }
        validateSourceCoverage(storyboard);
    }

    private static void validateSourceCoverage(StoryboardDocument storyboard) {
        if (storyboard.getSourceText() == null || storyboard.getSourceText().isBlank()) return;
        List<String> expected = splitSentences(storyboard.getSourceText());
        List<String> sceneNarration = new ArrayList<>();
        List<String> segmentSentences = new ArrayList<>();
        for (Scene scene : storyboard.getScenes()) {
            sceneNarration.addAll(splitSentences(scene.getNarration()));
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                segmentSentences.add(segment.getSentence() == null ? "" : segment.getSentence().trim());
            }
        }
        if (!expected.equals(sceneNarration)) {
            throw new IllegalStateException("Storyboard scene narration does not exactly preserve source sentence coverage and order");
        }
        if (!expected.equals(segmentSentences)) {
            throw new IllegalStateException("Storyboard segments do not exactly preserve source sentence coverage and order");
        }
    }

    private static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        List<String> result = new ArrayList<>();
        for (String value : normalized.split("(?<=[.!?।])\\s+")) {
            if (!value.isBlank()) result.add(value.trim());
        }
        return result.isEmpty() ? List.of(normalized) : result;
    }

    private static void validateSegment(Scene scene, SceneSegment segment) {
        List<String> labels = segment.getLabels() == null ? List.of() : segment.getLabels();
        List<String> placements = segment.getLabelPlacements() == null
            ? List.of() : segment.getLabelPlacements();
        String id = scene.getSceneNumber() + "." + segment.getSegmentNumber();

        if (StoryboardRules.requiresFormulaRenderer(segment)) {
            if (!"formula".equals(segment.getTemplate())
                    || !"formula/derivation".equals(segment.getVisualType())
                    || !"animation".equals(segment.getMediaType())
                    || !"local_animation".equals(segment.getMotionType())
                    || !"manim".equals(segment.getTool())
                    || segment.getFormulaLines() == null || segment.getFormulaLines().isEmpty()
                    || segment.getShot() != null || segment.getLtxShot() != null) {
                throw new IllegalStateException("Storyboard row " + id
                    + " contains formula content without the deterministic Manim contract");
            }
        }

        if (labels.isEmpty()) {
            if (declaresLabeledVisual(segment)) {
                throw new IllegalStateException("Storyboard row " + id
                    + " declares a labeled visual but has no labels");
            }
            if (!placements.isEmpty()) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has label placements without labels");
            }
            return;
        }

        if (!supportsLabels(segment)) {
            throw new IllegalStateException("Storyboard row " + id
                + " contains labels but its visual type does not support labels");
        }
        if (placements.size() != labels.size()) {
            throw new IllegalStateException("Storyboard row " + id
                + " must have exactly one placement per label");
        }
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                throw new IllegalStateException("Storyboard row " + id + " has a blank label");
            }
            if (isTooGenericLabel(label)) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has an overly generic label: " + label);
            }
            if (isCombinedLabel(label)) {
                throw new IllegalStateException("Storyboard row " + id
                    + " combines multiple targets in one label: " + label);
            }
            String prefix = label.toLowerCase(Locale.ROOT) + ":";
            String placement = placements.stream()
                .filter(value -> value != null
                    && value.toLowerCase(Locale.ROOT).trim().startsWith(prefix))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "Storyboard row " + id + " has no placement for label " + label));
            if (placement.toLowerCase(Locale.ROOT).contains("target=(auto)")
                    || !NUMERIC_TARGET.matcher(placement).find()) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has no numeric target for label " + label);
            }
            if (!hasSpecificTargetDescription(placement, label)) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has no specific target description for label " + label);
            }
            if (!placement.toLowerCase(Locale.ROOT)
                    .contains("anchor_source=post_render_visual_verification")
                    || !placement.toLowerCase(Locale.ROOT).contains("on_mismatch=skip_arrow")) {
                throw new IllegalStateException("Storyboard row " + id
                    + " does not require final-image verification for label " + label);
            }
        }
    }

    private static boolean hasSpecificTargetDescription(String placement, String label) {
        var matcher = Pattern.compile("(?i)target_description=([^;]+)").matcher(placement);
        if (!matcher.find()) return false;
        String description = matcher.group(1).trim();
        return description.length() >= 8
            && !description.equalsIgnoreCase(label)
            && !description.equalsIgnoreCase("exact visible " + label)
            && !description.matches("(?i).*(object|thing|area|detail).*" );
    }

    private static boolean isTooGenericLabel(String label) {
        String value = label.toLowerCase(Locale.ROOT).trim();
        return List.of("flower", "pollinator", "pollen", "nectar", "part", "component",
            "structure", "object", "item", "area").contains(value);
    }

    private static boolean isCombinedLabel(String label) {
        String value = label.toLowerCase(Locale.ROOT).trim();
        if (value.matches(".*[,;/|\\n].*") || value.matches(".*\\s+(?:and|&)\\s+.*")) {
            return true;
        }
        if (List.of("pollen grains", "pollen transfer path", "bee pollinator",
                "flower 1", "flower 2", "positive terminal", "negative terminal",
                "silver nitrate electrolyte").contains(value)) {
            return false;
        }
        int concepts = 0;
        for (String concept : List.of("anther", "stigma", "pollen", "filament", "style",
                "ovary", "nectar", "pollinator", "flower", "cathode", "anode",
                "electrolyte", "positive terminal", "negative terminal", "resistor",
                "switch", "ammeter", "voltmeter")) {
            if (Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(concept)
                    + "(?![\\p{L}\\p{N}])").matcher(value).find()) {
                concepts++;
            }
        }
        return concepts > 1;
    }

    private static boolean declaresLabeledVisual(SceneSegment segment) {
        return "labeled_image".equals(segment.getTemplate())
            || "realistic_labeled_image".equals(segment.getVisualType())
            || "realistic_background_with_labels".equals(segment.getVisualType())
            || "diagram_overlay".equals(segment.getVisualType())
            || "photo_with_labels".equals(segment.getMediaType())
            || "animation_with_labels".equals(segment.getMediaType());
    }

    private static boolean supportsLabels(SceneSegment segment) {
        return declaresLabeledVisual(segment)
            || "process_steps".equals(segment.getVisualType())
            || "split_screen_comparison".equals(segment.getVisualType())
            || "formula/derivation".equals(segment.getVisualType());
    }
}
