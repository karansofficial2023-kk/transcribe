package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Flags narration lines that look like garbled speech recognition ("Stop imbibing", or an unrelated line about tomatoes inside a
 * reproductive-system lesson). The narration is never changed or deleted: a flagged shot gets a note in its review notes and is listed
 * in the narration review file, so the subject-matter expert decides.
 * <ol>
 * <li>Deterministic signals (no model): a tiny fragment without a verb, a sentence repeating the one before it, a sentence sharing no
 * content word with the lesson/scene titles and the rest of its scene, a sentence ending mid-word or on a dangling connective.</li>
 * <li>Optional model pass, only for sentences flagged in step 1 (one call each): is it coherent educational narration for the lesson?
 * A model failure leaves the flag in place, marked "not_reviewed".</li>
 * </ol>
 */
public final class NarrationSanityCheck {

    private static final Logger logger = LoggerFactory.getLogger(NarrationSanityCheck.class);

    public interface Model {
        String generate(String system, String user) throws java.io.IOException;
    }

    public record Finding(String shot_id, String sentence, List<String> signals, String llm_verdict) {
    }

    private static final Set<String> STOP = Set.of("this", "that", "these", "those", "with", "from", "have", "has", "had", "were", "was",
        "been", "being", "will", "would", "could", "should", "their", "there", "then", "than", "them", "they", "what", "when", "where",
        "which", "while", "about", "into", "also", "both", "each", "such", "some", "many", "more", "most", "only", "over", "other",
        "your", "does", "done", "very", "much", "here", "just", "like", "make", "made", "because", "after", "before", "between", "through");
    private static final Set<String> VERB_WORDS = Set.of("is", "are", "was", "were", "be", "am", "has", "have", "had", "do", "does", "did",
        "can", "may", "will", "shall", "must", "might", "could", "would", "should", "make", "take", "give", "form", "show", "occur",
        "divide", "grow", "move", "flow", "react", "mean", "means", "need", "use", "see", "get", "go", "come", "become", "remember");
    private static final Set<String> DANGLING = Set.of("and", "or", "but", "the", "a", "an", "of", "to", "with", "because", "which", "that",
        "in", "on", "for", "by", "as", "if", "so", "than", "then", "from", "at", "is", "are", "was", "were");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private NarrationSanityCheck() {
    }

    /** Step 1 for one sentence of a scene; returns the signals that fired (empty = fine). Conservative by design. */
    public static List<String> signals(String lessonTitle, String sceneTitle, List<String> sceneSentences, int index) {
        List<String> out = new ArrayList<>();
        if (sceneSentences == null || index < 0 || index >= sceneSentences.size()) return out;
        String sentence = sceneSentences.get(index) == null ? "" : sceneSentences.get(index).strip();
        if (sentence.isEmpty()) return out;
        List<String> words = words(sentence);

        if (index > 0 && sceneSentences.get(index - 1) != null
                && normalize(sceneSentences.get(index - 1)).equals(normalize(sentence)) && !normalize(sentence).isEmpty()) {
            out.add("repeats the previous sentence");
        }

        if (words.size() > 0 && words.size() <= 12 && isLatin(sentence)) {
            Set<String> context = new LinkedHashSet<>(contentWords(lessonTitle));
            context.addAll(contentWords(sceneTitle));
            for (int i = 0; i < sceneSentences.size(); i++) {
                if (i != index) context.addAll(contentWords(sceneSentences.get(i)));
            }
            Set<String> own = contentWords(sentence);
            boolean shares = false;
            for (String w : own) {
                for (String c : context) {
                    if (sameStem(w, c)) {
                        shares = true;
                        break;
                    }
                }
                if (shares) break;
            }
            if (context.size() >= 3 && !shares) {
                boolean verb = words.stream().anyMatch(NarrationSanityCheck::verbLike);
                if (words.size() <= 3 && !verb) out.add("very short fragment without a verb");
                if (!own.isEmpty()) out.add("shares no topic word with the lesson or its scene");
            }
        }

		if (sentence.endsWith("-")) {
            out.add("ends mid-word");
        } else if (!words.isEmpty() && words.size() >= 3 && DANGLING.contains(words.get(words.size() - 1).toLowerCase(Locale.ROOT))) {
            out.add("ends on a dangling connective");
        }
        return out;
    }

    /** Runs both steps over the storyboard; notes go to the segments' review notes, narration text is untouched. */
    public static List<Finding> check(StoryboardDocument storyboard, Model model) {
        List<Finding> findings = new ArrayList<>();
        if (storyboard == null || storyboard.getScenes() == null) return findings;
        boolean modelUp = model != null;
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            List<String> sentences = new ArrayList<>();
            for (SceneSegment segment : scene.getSegments()) sentences.add(segment.getSentence() == null ? "" : segment.getSentence());
            for (int i = 0; i < scene.getSegments().size(); i++) {
                SceneSegment segment = scene.getSegments().get(i);
                String sentence = sentences.get(i);
                if (segment.getHeading() != null && segment.getHeading().strip().equalsIgnoreCase(sentence.strip())) continue;
                List<String> signals;
                try {
                    signals = signals(storyboard.getTitle(), scene.getSceneTitle(), sentences, i);
                } catch (RuntimeException e) {
                    continue;
                }
                if (signals.isEmpty()) continue;
                String verdict = "not_reviewed";
                String reason = String.join("; ", signals);
                if (modelUp) {
                    try {
                        JsonObject answer = ask(model, storyboard.getTitle(), scene.getSceneTitle(), sentence);
                        boolean coherent = answer.has("coherent") && answer.get("coherent").getAsBoolean();
                        verdict = coherent ? "coherent" : "incoherent";
                        if (!coherent && answer.has("reason") && !answer.get("reason").getAsString().isBlank()) {
                            reason = answer.get("reason").getAsString().strip() + " (" + reason + ")";
                        }
                    } catch (Exception e) {
                        logger.warn("Narration check model call failed ({}); remaining flags stay unreviewed", e.getMessage());
                        modelUp = false;                      // do not wait for a dead model once per sentence
                    }
                }
                findings.add(new Finding(scene.getSceneNumber() + "." + segment.getSegmentNumber(), sentence, signals, verdict));
                if (!"coherent".equals(verdict)) {
                    String note = "NARRATION CHECK: " + reason + ("not_reviewed".equals(verdict) ? " (not reviewed by model)" : "") + " - SME to confirm";
                    String old = segment.getCoverageNotes();
                    segment.setCoverageNotes(old == null || old.isBlank() ? note : old.strip() + "\n" + note);
                }
            }
        }
        return findings;
    }

    private static JsonObject ask(Model model, String lesson, String scene, String sentence) throws java.io.IOException {
        String answer = model.generate("You check one line of narration for a school lesson titled '" + lesson + "' (scene '" + scene
            + "'). The line comes from speech recognition and may be garbled or off-topic. Decide whether it is coherent educational "
            + "narration that belongs in this lesson. Return only JSON {\"coherent\": true|false, \"reason\": \"short\"}.", sentence);
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end < start) throw new java.io.IOException("no JSON in answer");
        try {
            return JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new java.io.IOException("unusable answer", e);
        }
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = WORD.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static Set<String> contentWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(text)) {
            String lower = w.toLowerCase(Locale.ROOT);
            if (lower.length() >= 4 && !STOP.contains(lower)) out.add(lower);
        }
        return out;
    }

    private static boolean sameStem(String a, String b) {
        String x = stem(a);
        String y = stem(b);
        if (x.equals(y)) return true;
        return x.length() >= 5 && y.length() >= 5 && x.regionMatches(0, y, 0, 5);
    }

    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static boolean verbLike(String w) {
        String lower = w.toLowerCase(Locale.ROOT);
        return VERB_WORDS.contains(lower) || (lower.length() > 4 && (lower.endsWith("ed") || lower.endsWith("ing") || lower.endsWith("es")
            || lower.endsWith("s") && !lower.endsWith("ss")));
    }

    private static boolean isLatin(String text) {
        int letters = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c < 0x250) latin++;
            }
        }
        return letters > 0 && latin >= letters * 0.9;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
