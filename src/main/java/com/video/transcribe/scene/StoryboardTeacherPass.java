package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The read-through a teacher gives a storyboard before it goes to production. Subject-independent.
 * <ol>
 * <li>Relevance: shots whose narration is not about the lesson at all (proverbs and metaphors, channel promotion, personal
 * anecdotes, "this video demonstrates the old saying ...") are removed; the model only nominates, and a shot that carries a
 * formula or labels is never removed.</li>
 * <li>Headings: a heading must describe its own shot. A heading that is just the start of a sentence, or that shares no content word
 * with the shot's narration (it was written for a neighbouring shot), is replaced by the scene title or by the shot's key phrase.</li>
 * </ol>
 */
public final class StoryboardTeacherPass {

    private static final Logger logger = LoggerFactory.getLogger(StoryboardTeacherPass.class);

    public interface Model {
        String generate(String system, String user) throws java.io.IOException;
    }

    public record Report(List<String> removed, List<String> renamed) {
    }

    private static final Set<String> STOP = Set.copyOf(java.util.Arrays.asList(("a an the of and or to in on at for from with by as is are was were be this that these those it its "
        + "their his her our your we you they he she can will would may might should must do does did has have had not no so if then than "
        + "also just only very such into about over under after before which what when where who how why let let's us here there now "
        // words that never name a topic: "this option is indeed the correct one" has no title in it
        + "correct right wrong option options answer answers indeed one ones thing things way really actually clear okay yes see look "
        + "going get got say said like well know quite simply").split(" ")));

    private StoryboardTeacherPass() {
    }

    public static Report apply(StoryboardDocument storyboard, Model model) {
        List<String> removed = removeOffTopic(storyboard, model);
        List<String> renamed = fixHeadings(storyboard);
        if (!removed.isEmpty() || !renamed.isEmpty()) {
            logger.info("Teacher pass: removed {} off-topic shot(s) {}, fixed {} heading(s)", removed.size(), removed, renamed.size());
        }
        return new Report(removed, renamed);
    }

    static List<String> removeOffTopic(StoryboardDocument storyboard, Model model) {
        List<String> removed = new ArrayList<>();
        if (model == null || storyboard.getScenes() == null) return removed;
        StringBuilder listing = new StringBuilder();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                listing.append(scene.getSceneNumber()).append('.').append(segment.getSegmentNumber()).append(": ")
                    .append(segment.getSentence()).append('\n');
            }
        }
        String answer;
        try {
            answer = model.generate("You review a lesson storyboard as an experienced teacher. Lesson: '" + storyboard.getTitle()
                + "'. List the shots whose narration is NOT about the lesson's subject matter at all: proverbs or metaphors used only "
                + "as decoration, channel or video promotion, personal anecdotes, motivational sayings. Questions to the student, "
                + "greetings, recaps, examples and anything that teaches the topic are ON topic. When unsure, it is on topic. "
                + "Return JSON {\"off_topic\": [\"scene.shot\", ...]}.", listing.toString());
        } catch (Exception e) {
            logger.warn("Relevance check skipped: {}", e.getMessage());
            return removed;
        }
        Set<String> flagged = new HashSet<>();
        try {
            int start = answer.indexOf('{');
            JsonObject json = JsonParser.parseString(answer.substring(start, answer.lastIndexOf('}') + 1)).getAsJsonObject();
            JsonElement list = json.get("off_topic");
            if (list instanceof JsonArray array) array.forEach(item -> flagged.add(item.getAsString().trim()));
        } catch (RuntimeException e) {
            logger.warn("Relevance check returned no usable answer");
            return removed;
        }
        int total = storyboard.getScenes().stream().mapToInt(s -> s.getSegments() == null ? 0 : s.getSegments().size()).sum();
        if (flagged.size() > Math.max(1, total / 4)) {
            logger.warn("Relevance check flagged {} of {} shots; too many to trust, nothing removed", flagged.size(), total);
            return removed;
        }
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            List<SceneSegment> kept = new ArrayList<>();
            for (SceneSegment segment : scene.getSegments()) {
                String id = scene.getSceneNumber() + "." + segment.getSegmentNumber();
                boolean ownContent = notEmpty(segment.getFormulaLines()) || notEmpty(segment.getLabels()) || "title_card".equals(segment.getTemplate());
                if (flagged.contains(id) && !ownContent && scene.getSegments().size() > 1) {
                    removed.add(id + " \"" + segment.getSentence() + "\"");
                    String title = norm(scene.getSceneTitle());
                    if (!title.isEmpty() && norm(segment.getSentence()).startsWith(title)) {
                        scene.setSceneTitle("");          // the title came from the removed sentence: fixHeadings gives the scene a new one
                    }
                    if (scene.getNarration() != null && segment.getSentence() != null) {
                        scene.setNarration(scene.getNarration().replace(segment.getSentence(), "").replaceAll("\\s{2,}", " ").trim());
                    }
                    continue;
                }
                kept.add(segment);
            }
            for (int i = 0; i < kept.size(); i++) kept.get(i).setSegmentNumber(i + 1);
            scene.setSegments(kept);
        }
        // a scene whose every shot was off-topic ("Stay tuned for our next video") goes as a whole; the others are renumbered
        List<Scene> scenes = new ArrayList<>();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() != null && !scene.getSegments().isEmpty()) scenes.add(scene);
        }
        if (!scenes.isEmpty() && scenes.size() < storyboard.getScenes().size()) {
            for (int i = 0; i < scenes.size(); i++) scenes.get(i).setSceneNumber(i + 1);
            storyboard.setScenes(scenes);
        }
        return removed;
    }

    /**
     * Only headings that are evidently wrong are replaced: the start of the shot's own sentence ("This simplifies to A0 divided by
     * 1.1."), or text copied from another shot's sentence. A topical heading that simply does not repeat the shot's words
     * ("Resistance After Stretching a Wire" over "Substituting the values ...") is a good heading and stays. The replacement is a
     * 2-4 word key phrase of the shot, else the nearest good heading before it in the scene, else the lesson title. Scene titles
     * that are only the start of their first sentence get the scene's first good shot heading.
     */
    static List<String> fixHeadings(StoryboardDocument storyboard) {
        List<String> renamed = new ArrayList<>();
        if (storyboard.getScenes() == null) return renamed;
        List<String> sentences = new ArrayList<>();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() != null) scene.getSegments().forEach(s -> sentences.add(norm(s.getSentence())));
        }
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            List<String> original = scene.getSegments().stream()
                .map(seg -> seg.getHeading() == null ? "" : seg.getHeading().trim()).toList();
            String firstGood = original.stream().filter(h -> !h.isEmpty() && !sentenceLike(h, sentences)).findFirst().orElse("");
            String lastGood = "";
            for (SceneSegment segment : scene.getSegments()) {
                String heading = segment.getHeading() == null ? "" : segment.getHeading().trim();
                String sentence = segment.getSentence() == null ? "" : segment.getSentence();
                if ("title_card".equals(segment.getTemplate()) || heading.isEmpty() || sentence.isBlank()) {
                    continue;
                }
                if (!sentenceLike(heading, sentences)) {
                    lastGood = heading;
                    continue;
                }
                String key = keyPhrase(sentence);
                String replacement = key.split(" ").length >= 2 ? key
                    : !lastGood.isBlank() ? lastGood : !firstGood.isBlank() ? firstGood
                    : (storyboard.getTitle() == null ? "" : storyboard.getTitle().trim());
                if (!replacement.isBlank() && !replacement.equalsIgnoreCase(heading)) {
                    renamed.add(scene.getSceneNumber() + "." + segment.getSegmentNumber() + ": '" + heading + "' -> '" + replacement + "'");
                    segment.setHeading(replacement);
                    lastGood = replacement;
                }
            }
            String title = scene.getSceneTitle() == null ? "" : scene.getSceneTitle().trim();
            if (title.isEmpty() || sentenceLike(title, sentences)) {
                String good = !firstGood.isBlank() ? firstGood : scene.getSegments().stream().map(SceneSegment::getHeading)
                    .filter(h -> h != null && !h.isBlank() && !sentenceLike(h, sentences)).findFirst().orElse("");
                if (!good.isBlank()) {
                    renamed.add("scene " + scene.getSceneNumber() + ": '" + title + "' -> '" + good + "'");
                    scene.setSceneTitle(good);
                }
            }
        }
        return renamed;
    }

    /** A heading that is the beginning of (or equal to) a narration sentence, or ends like a sentence, is not a title. */
    static boolean sentenceLike(String heading, List<String> normalisedSentences) {
        String h = norm(heading);
        if (h.isEmpty()) return false;
        if (heading.trim().matches(".*[.?!]$")) return true;
        if (h.split(" ").length < 3) return false;           // two-word titles ("Weak Electrolytes") are fine even if a sentence starts so
        return normalisedSentences.stream().anyMatch(sentence -> sentence.startsWith(h));
    }

    /** The longest run (max 4) of content words in the sentence, title-cased: "the limiting molar conductivity of ..." -> "Limiting Molar Conductivity". */
    static String keyPhrase(String sentence) {
        List<String> best = List.of();
        List<String> run = new ArrayList<>();
        for (String raw : sentence.split("\\s+")) {
            String word = raw.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}'-]+$", "");
            boolean content = word.length() > 2 && !STOP.contains(word.toLowerCase(Locale.ROOT)) && !word.matches("\\d+");
            if (content) {
                run.add(word);
                if (run.size() > best.size()) best = new ArrayList<>(run.subList(Math.max(0, run.size() - 4), run.size()));
            } else {
                run = new ArrayList<>();
            }
            if (raw.matches(".*[,;:.?!]$")) run = new ArrayList<>();
        }
        StringBuilder out = new StringBuilder();
        for (String word : best) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static boolean sharesWord(String a, String b) {
        Set<String> words = new HashSet<>(content(a));
        words.retainAll(content(b));
        return !words.isEmpty();
    }

    private static Set<String> content(String text) {
        Set<String> out = new HashSet<>();
        for (String token : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (token.length() > 2 && !STOP.contains(token)) out.add(token.length() > 5 ? token.substring(0, 5) : token);
        }
        return out;
    }

    private static String norm(String text) {
        return (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }
}
