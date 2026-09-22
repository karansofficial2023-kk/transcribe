package com.video.transcribe.scene;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Final invariant check before storyboard JSON or DOCX is written. */
public final class StoryboardQualityGate {
    private static final String LABEL_STYLE = "high_contrast_box; white_text; dark_background; "
        + "colored_target_dot; 3px_leader_line; 28px_minimum_font; avoid_subject; "
        + "avoid_title_area; avoid_subtitle_area; avoid_logo_area";
    private static final String SUBTITLE_STYLE = "bottom_band; band_color=black; band_opacity=0.55; "
        + "text_color=white; font_size=42; max_lines=2; align=center; "
        + "horizontal_margin=120; bottom_margin=55";
    private static final List<String> VISUAL_TYPES = List.of(
        "title_card", "realistic_image", "realistic_labeled_image",
        "realistic_background_with_labels", "diagram_overlay", "process_steps",
        "short_motion_clip");
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
        int titleCards = 0;
        boolean firstShot = true;
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                validateSegment(scene, segment);
                if ("title_card".equals(segment.getVisualType())) {
                    titleCards++;
                    if (!firstShot) {
                        throw new IllegalStateException("title_card is allowed only for the first shot");
                    }
                }
                firstShot = false;
            }
        }
        if (titleCards != 1) {
            throw new IllegalStateException("Storyboard must contain exactly one opening title_card");
        }
        SceneSegment first = storyboard.getScenes().stream()
            .filter(scene -> scene.getSegments() != null && !scene.getSegments().isEmpty())
            .findFirst().orElseThrow(() -> new IllegalStateException("Storyboard contains no shots"))
            .getSegments().get(0);
        if (storyboard.getTitle() != null && !storyboard.getTitle().isBlank()
                && !storyboard.getTitle().equals(first.getHeading())) {
            throw new IllegalStateException("Opening title_card heading must equal the storyboard title");
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

        if (!VISUAL_TYPES.contains(segment.getVisualType())) {
            throw new IllegalStateException("Storyboard row " + id
                + " has unsupported visual_type: " + segment.getVisualType());
        }
        if (!SUBTITLE_STYLE.equals(segment.getSubtitleStyle())) {
            throw new IllegalStateException("Storyboard row " + id + " has non-standard subtitle style");
        }
        if (segment.getSubtitle() == null || segment.getSubtitle().isBlank()
                || segment.getSubtitle().contains("...")) {
            throw new IllegalStateException("Storyboard row " + id + " has an incomplete subtitle");
        }
        if (requiresCleanImagePrompt(segment) && !hasCleanImageContract(segment.getComfyPrompt())) {
            throw new IllegalStateException("Storyboard row " + id + " has an unsafe image prompt");
        }

        if (StoryboardRules.requiresFormulaRenderer(segment)) {
            if (!"formula".equals(segment.getTemplate())
                    || !"process_steps".equals(segment.getVisualType())
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
            if (segment.getMotion() != null
                    && segment.getMotion().toLowerCase(Locale.ROOT).contains("arrow_draw_then_label_fade")) {
                throw new IllegalStateException("Storyboard row " + id
                    + " requests label animation with an empty label list");
            }
            return;
        }

        if ("short_motion_clip".equals(segment.getVisualType())
                || "video_broll".equals(segment.getTemplate())) {
            throw new IllegalStateException("Storyboard row " + id
                + " places scientific labels on moving footage");
        }

        if (!supportsLabels(segment)) {
            throw new IllegalStateException("Storyboard row " + id
                + " contains labels but its visual type does not support labels");
        }
        if (placements.size() != labels.size()) {
            throw new IllegalStateException("Storyboard row " + id
                + " must have exactly one placement per label");
        }
        if (!LABEL_STYLE.equals(segment.getLabelStyle())) {
            throw new IllegalStateException("Storyboard row " + id + " has non-standard label style");
        }
        if (segment.getMotion() == null
                || !segment.getMotion().toLowerCase(Locale.ROOT).contains("arrow_draw_then_label_fade")) {
            throw new IllegalStateException("Storyboard row " + id + " lacks labeled reveal motion");
        }
        double minimumDuration = Math.max(segment.getEstimatedNarrationSeconds(), labels.size() + 2.5);
        if (segment.getRecommendedClipSeconds() + 0.001 < minimumDuration) {
            throw new IllegalStateException("Storyboard row " + id + " is too short for its labels");
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
            String prefix = label.toLowerCase(Locale.ROOT);
            String placement = placements.stream()
                .filter(value -> value != null
                    && placementMatchesLabel(value, prefix))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "Storyboard row " + id + " has no placement for label " + label));
            boolean autoVerify = placement.toLowerCase(Locale.ROOT).contains("target_xy: auto_verify");
            boolean lockedNumeric = segment.getAssetPath() != null && !segment.getAssetPath().isBlank()
                && (NUMERIC_TARGET.matcher(placement).find() || hasNumericTargetXy(placement));
            if (!autoVerify && !lockedNumeric) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has neither AUTO_VERIFY nor a locked-asset coordinate for label " + label);
            }
            if (!hasSpecificTargetDescription(placement, label)) {
                throw new IllegalStateException("Storyboard row " + id
                    + " has no specific target description for label " + label);
            }
        }
    }

    private static boolean placementMatchesLabel(String placement, String lowerLabel) {
        String value = placement.toLowerCase(Locale.ROOT).trim();
        if (!value.startsWith(lowerLabel)) return false;
        String remainder = value.substring(lowerLabel.length()).trim();
        return remainder.startsWith("|") || remainder.startsWith(":");
    }

    private static boolean hasNumericTargetXy(String placement) {
        return Pattern.compile("(?i)target_xy:\\s*" + NUMBER + "\\s*,\\s*" + NUMBER)
            .matcher(placement).find();
    }

    private static boolean requiresCleanImagePrompt(SceneSegment segment) {
        return !"short_motion_clip".equals(segment.getVisualType());
    }

    private static boolean hasCleanImageContract(String prompt) {
        if (prompt == null) return false;
        String value = prompt.toLowerCase(Locale.ROOT);
        return value.contains("1920x1080")
            && value.contains("no embedded text")
            && value.contains("no generated labels")
            && value.contains("no generated arrows")
            && value.contains("no captions")
            && value.contains("no watermark")
            && value.contains("no slide or presentation-card layout");
    }

    private static boolean hasSpecificTargetDescription(String placement, String label) {
        String[] pipeParts = placement.split("\\|", -1);
        if (pipeParts.length >= 3) {
            String description = pipeParts[1].trim();
            return description.length() >= 8
                && !description.equalsIgnoreCase(label)
                && !description.matches("(?i).*(object|thing|area|detail).*" );
        }
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
            || "diagram_overlay".equals(segment.getVisualType())
            || "process_steps".equals(segment.getVisualType());
    }
}
