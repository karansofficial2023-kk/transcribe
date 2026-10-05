package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.video.transcribe.LanguageSupport;
import com.video.transcribe.LanguageSupport.Language;

/**
 * Produces the same storyboard in another language without re-planning it.
 *
 * Everything that is read aloud or shown on screen (narration, headings, subtitles, labels, steps, circuit/graph captions) is
 * translated; everything structural stays exactly as planned (visual types, image prompts, formulas, coordinates, durations'
 * inputs). So a translation takes minutes instead of the half hour of re-planning, and the shots and pictures line up with the
 * original storyboard.
 *
 * Every translated string is validated (script, numbers, formulas, length). Strings that still fail after a retry are kept but
 * flagged "TRANSLATION CHECK" in the shot's review notes.
 */
public final class StoryboardTranslator {
    private static final Logger logger = LoggerFactory.getLogger(StoryboardTranslator.class);
    private static final Gson GSON = new Gson();

    /** Translates texts one-to-one; implemented by a language model, or by a fake in tests. */
    @FunctionalInterface
    public interface TextTranslator {
        List<String> translate(List<String> texts, Language source, Language target) throws Exception;
    }

    public record Result(StoryboardDocument storyboard, int strings, int retried, int flagged, int drifted,
                         List<java.util.Map<String, String>> reviews) {
    }

    private record Slot(String where, String text, Consumer<String> apply, SceneSegment segment) {
    }

    private static final Pattern FORMULA_TOKEN = Pattern.compile("[A-Z][A-Za-z]*\\d[A-Za-z0-9]*[⁺⁻+\\-]*|[A-Z][a-z]?[⁺⁻]");
    private static final Pattern STRUCTURED_ROW = Pattern.compile("(?i)^\\s*(series|parallel|electrolysis)\\s*:");

    /** A translation whose back-translation keeps less than this share of the original's key words is flagged for review. */
    static final double DRIFT_BELOW = 0.40;

    private final TextTranslator translator;
    private final int batchSize;
    private final boolean backCheck;

    public StoryboardTranslator(TextTranslator translator) {
        this(translator, 14, false);
    }

    public StoryboardTranslator(TextTranslator translator, int batchSize) {
        this(translator, batchSize, false);
    }

    /** @param backCheck translate every result back to the source language and flag strings whose meaning drifted */
    public StoryboardTranslator(TextTranslator translator, int batchSize, boolean backCheck) {
        this.translator = translator;
        this.batchSize = Math.max(1, batchSize);
        this.backCheck = backCheck;
    }

    public Result translate(StoryboardDocument source, Language from, Language to) throws Exception {
        if (from.code().equals(to.code())) throw new IllegalArgumentException("Source and target language are both " + to.name());
        StoryboardDocument copy = GSON.fromJson(GSON.toJson(source), StoryboardDocument.class);
        List<Slot> slots = new ArrayList<>();
        collect(copy, slots);

        // each distinct string is translated once (subtitles repeat the narration, headings repeat across shots)
        java.util.Map<String, List<Slot>> byText = new java.util.LinkedHashMap<>();
        for (Slot slot : slots) byText.computeIfAbsent(slot.text(), key -> new ArrayList<>()).add(slot);
        List<String> unique = new ArrayList<>(byText.keySet());

        int retried = 0;
        int flagged = 0;
        List<java.util.Map<String, String>> reviews = new ArrayList<>();
        java.util.Map<String, String> accepted = new java.util.LinkedHashMap<>();
        for (int from0 = 0; from0 < unique.size(); from0 += batchSize) {
            List<String> sources = unique.subList(from0, Math.min(unique.size(), from0 + batchSize));
            List<String> translated = new ArrayList<>(translator.translate(sources, from, to));
            if (translated.size() != sources.size()) {
                throw new IllegalStateException("Translator returned " + translated.size() + " strings for " + sources.size());
            }
            for (int i = 0; i < sources.size(); i++) {
                String text = sources.get(i);
                translated.set(i, cleanup(text, translated.get(i)));
                String problem = problem(text, translated.get(i), to);
                if (problem != null) {
                    retried++;
                    String again = cleanup(text, translator.translate(List.of(text), from, to).get(0));
                    if (problem(text, again, to) == null || translated.get(i) == null || translated.get(i).isBlank()) {
                        translated.set(i, again);              // a retry is only taken when it is clean (or the first answer was empty)
                    }
                    problem = problem(text, translated.get(i), to);
                }
                String result = translated.get(i) == null ? "" : translated.get(i).trim();
                accepted.put(text, result);
                for (Slot slot : byText.get(text)) {
                    if (slot.where().equals("title") && !result.isEmpty()) {
                        reviews.add(review("title", text, result, "titles are short, so automatic checks are weak: always read the title"));
                    }
                    if (problem != null) {
                        flagged++;
                        flag(slot.segment(), slot.where() + ": " + problem);
                        reviews.add(review(slot.where(), text, result, problem));
                        logger.warn("Translation check failed at {}: {}", slot.where(), problem);
                    }
                    if (!result.isEmpty()) slot.apply().accept(result);
                }
            }
        }
        int drifted = backCheck ? checkDrift(accepted, byText, from, to, reviews) : 0;
        for (Scene scene : copy.getScenes() == null ? List.<Scene>of() : copy.getScenes()) {
            if (scene.getSegments() == null) continue;
            StringBuilder narration = new StringBuilder();
            for (SceneSegment segment : scene.getSegments()) {
                if (segment.getSentence() != null) narration.append(narration.length() > 0 ? " " : "").append(segment.getSentence());
            }
            scene.setNarration(narration.toString());       // scene narration = joined shot narration, rebuilt rather than translated twice
        }
        return new Result(copy, slots.size(), retried, flagged, drifted, reviews);
    }

    /** Translates the results back and flags strings whose key words no longer match the original: wrong meaning, not wrong script. */
    private int checkDrift(java.util.Map<String, String> accepted, java.util.Map<String, List<Slot>> byText, Language from, Language to,
                           List<java.util.Map<String, String>> reviews) throws Exception {
        List<String> originals = new ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : accepted.entrySet()) {
            if (entry.getValue().isEmpty() || entry.getKey().codePoints().filter(Character::isLetter).count() < 25) continue;
            originals.add(entry.getKey());
        }
        int drifted = 0;
        for (int start = 0; start < originals.size(); start += batchSize) {
            List<String> group = originals.subList(start, Math.min(originals.size(), start + batchSize));
            List<String> back = translator.translate(group.stream().map(accepted::get).toList(), to, from);
            for (int i = 0; i < group.size(); i++) {
                double kept = overlap(group.get(i), back.get(i));
                if (kept < DRIFT_BELOW) {
                    drifted++;
                    String note = "meaning may have drifted (back-translation keeps " + Math.round(kept * 100) + "% of the key words)";
                    for (Slot slot : byText.get(group.get(i))) {
                        flag(slot.segment(), slot.where() + ": " + note);
                        reviews.add(review(slot.where(), group.get(i), accepted.get(group.get(i)), note));
                    }
                    logger.warn("Translation drift at {}: {}", byText.get(group.get(i)).get(0).where(), note);
                }
            }
        }
        return drifted;
    }

    /** Share of the original's key-word stems that survive in the back-translation. */
    static double overlap(String original, String backTranslation) {
        java.util.Set<String> a = ClaimGrounding.stems(original);
        if (a.isEmpty()) return 1;
        java.util.Set<String> b = ClaimGrounding.stems(backTranslation);
        long kept = a.stream().filter(b::contains).count();
        return (double) kept / a.size();
    }

    /**
     * Mechanical tidy-up of machine output: drops English glosses the source did not have ("... (electrolyte)") and re-separates
     * numbers glued to native-script words ("ஒரு6வோல்ட்"). Formulas such as H2O are not touched.
     */
    static String cleanup(String source, String translation) {
        if (translation == null) return null;
        String text = translation.trim();
        if (!source.contains("(")) {
            text = text.replaceAll("\\s*\\([A-Za-z][A-Za-z0-9 ,'\\-]*\\)", "");
        }
        text = text.replaceAll("(?<=[\\p{L}\\p{M}&&[^A-Za-z]])(?=\\d)", " ").replaceAll("(?<=\\d)(?=[\\p{L}\\p{M}&&[^A-Za-z]])", " ");
        return text.replaceAll("\\s{2,}", " ").trim();
    }

    // ------------------------------------------------------------------ what gets translated

    private static void collect(StoryboardDocument doc, List<Slot> slots) {
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) {
            slots.add(new Slot("title", doc.getTitle(), doc::setTitle, null));
        }
        if (doc.getScenes() == null) return;
        for (Scene scene : doc.getScenes()) {
            String sceneId = "scene " + scene.getSceneNumber();
            if (scene.getSceneTitle() != null && !scene.getSceneTitle().isBlank()) {
                slots.add(new Slot(sceneId + " title", scene.getSceneTitle(), scene::setSceneTitle, null));
            }
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                collectSegment(sceneId + "." + segment.getSegmentNumber(), segment, slots);
            }
        }
    }

    private static void collectSegment(String id, SceneSegment segment, List<Slot> slots) {
        add(slots, id + " narration", segment.getSentence(), segment::setSentence, segment);
        add(slots, id + " heading", segment.getHeading(), segment::setHeading, segment);
        add(slots, id + " subtitle", segment.getSubtitle(), segment::setSubtitle, segment);

        List<String> labels = segment.getLabels() == null ? List.of() : segment.getLabels();
        List<String> placements = segment.getLabelPlacements() == null ? List.of() : new ArrayList<>(segment.getLabelPlacements());
        List<String> newLabels = new ArrayList<>(labels);
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            String label = labels.get(i);
            add(slots, id + " label " + (i + 1), label, translated -> {
                newLabels.set(index, translated);
                segment.setLabels(new ArrayList<>(newLabels));
                renamePlacement(placements, label, translated);
                segment.setLabelPlacements(new ArrayList<>(placements));
            }, segment);
        }
        translateList(slots, id + " step", segment.getSteps(), segment::setSteps, segment);
        translateList(slots, id + " explain", segment.getExplainSteps(), segment::setExplainSteps, segment);
        if (segment.getColumns() != null) {
            List<String> columns = new ArrayList<>(segment.getColumns());
            for (int i = 0; i < columns.size(); i++) {
                final int index = i;
                String row = columns.get(i);
                String part = translatablePart(segment, row);
                if (part == null) continue;
                add(slots, id + " row " + (i + 1), part, translated -> {
                    columns.set(index, replacePart(segment, row, translated));
                    segment.setColumns(new ArrayList<>(columns));
                }, segment);
            }
        }
        // formulaLines, image prompts, coordinates, asset paths, motion and templates are structural: left exactly as planned
    }

    private static void add(List<Slot> slots, String where, String text, Consumer<String> apply, SceneSegment segment) {
        if (text != null && !text.isBlank() && text.codePoints().anyMatch(Character::isLetter)) {
            slots.add(new Slot(where, text, apply, segment));
        }
    }

    private static void translateList(List<Slot> slots, String where, List<String> values, Consumer<List<String>> setter, SceneSegment segment) {
        if (values == null || values.isEmpty()) return;
        List<String> copy = new ArrayList<>(values);
        for (int i = 0; i < values.size(); i++) {
            final int index = i;
            add(slots, where + " " + (i + 1), values.get(i), translated -> {
                copy.set(index, translated);
                setter.accept(new ArrayList<>(copy));
            }, segment);
        }
    }

    /** The human-readable part of a structured row: a circuit caption, a graph label, both halves of "A | B". */
    static String translatablePart(SceneSegment segment, String row) {
        if (row == null || row.isBlank()) return null;
        String template = segment.getTemplate() == null ? "" : segment.getTemplate();
        if ("circuit".equals(template)) {
            return row.contains("|") ? row.substring(0, row.indexOf('|')).trim() : null;
        }
        if ("graph".equals(template)) {
            Matcher m = Pattern.compile("^(.+?)\\s*[:=]\\s*[-\\d.,]+").matcher(row);
            return m.find() ? m.group(1).trim() : null;
        }
        if (STRUCTURED_ROW.matcher(row.contains("|") ? row.substring(row.indexOf('|') + 1) : "").find()) {
            return row.substring(0, row.indexOf('|')).trim();
        }
        return row.codePoints().anyMatch(Character::isLetter) ? row : null;
    }

    static String replacePart(SceneSegment segment, String row, String translated) {
        String template = segment.getTemplate() == null ? "" : segment.getTemplate();
        if ("circuit".equals(template) && row.contains("|")) {
            return translated + " " + row.substring(row.indexOf('|'));
        }
        if ("graph".equals(template)) {
            Matcher m = Pattern.compile("^(.+?)(\\s*[:=]\\s*[-\\d.,].*)$").matcher(row);
            return m.matches() ? translated + m.group(2) : row;
        }
        if (STRUCTURED_ROW.matcher(row.contains("|") ? row.substring(row.indexOf('|') + 1) : "").find()) {
            return translated + " " + row.substring(row.indexOf('|'));
        }
        return translated;
    }

    /** Label placements start with the label ("Anode: box=left; target=..."); keep them keyed to the translated label. */
    static void renamePlacement(List<String> placements, String oldLabel, String newLabel) {
        for (int i = 0; i < placements.size(); i++) {
            String placement = placements.get(i);
            int colon = placement.indexOf(':');
            if (colon > 0 && placement.substring(0, colon).trim().equalsIgnoreCase(oldLabel.trim())) {
                placements.set(i, newLabel + placement.substring(colon));
            }
        }
    }

    // ------------------------------------------------------------------ validation

    /** Null when the translation is acceptable, otherwise a short reason. */
    static String problem(String source, String translation, Language target) {
        if (translation == null || translation.isBlank()) return "empty translation";
        long sourceLetters = source.codePoints().filter(Character::isLetter).count();
        long letters = translation.codePoints().filter(Character::isLetter).count();
        // Short labels and headings skip the script-share rule below, so an untranslated one ("Anode" on a Tamil video) needs its own check.
        if (sourceLetters >= 4 && translation.strip().equalsIgnoreCase(source.strip())
                && !"en".equals(target.code()) && LanguageSupport.scriptShare(source, target) < 0.5) {
            return "returned unchanged (not translated)";
        }
        if (sourceLetters >= 12 && LanguageSupport.scriptShare(translation, target) < 0.5) {
            return "not written in " + target.name() + " (" + Math.round(LanguageSupport.scriptShare(translation, target) * 100) + "% in script)";
        }
        java.util.Set<String> kept = ClaimGrounding.digitNumbers(translation);
        for (String number : ClaimGrounding.digitNumbers(source)) {
            if (!kept.contains(number)) return "number " + number + " is missing";
        }
        Matcher formula = FORMULA_TOKEN.matcher(source);
        while (formula.find()) {
            if (!translation.contains(formula.group())) return "formula or symbol '" + formula.group() + "' was changed";
        }
        if (sourceLetters >= 12) {
            double ratio = (double) letters / sourceLetters;
            if (ratio < 0.35 || ratio > 3.5) return "length is implausible (" + String.format(Locale.ROOT, "%.1f", ratio) + "x the original)";
        }
        return null;
    }

    private static java.util.Map<String, String> review(String where, String source, String translation, String problem) {
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("where", where);
        entry.put("source", source);
        entry.put("translation", translation);
        entry.put("problem", problem);
        return entry;
    }

    private static void flag(SceneSegment segment, String note) {
        if (segment == null) return;
        String existing = segment.getCoverageNotes();
        String text = "TRANSLATION CHECK: " + note + ". Translator review required.";
        segment.setCoverageNotes(existing == null || existing.isBlank() ? text : existing + "; " + text);
    }
}
