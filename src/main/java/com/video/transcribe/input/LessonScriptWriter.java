package com.video.transcribe.input;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.llm.OllamaClient;

/**
 * Writes the narration for a lesson that exists only as a topic, then has it checked by an independent review pass.
 *
 * There is no recorded teacher to compare against, so the facts come from the model (or, when the materials folder holds approved
 * notes for this lesson, from those notes). The review is therefore a separate request with a different role and stricter rules:
 * it corrects what it can, removes what it cannot confirm, and lists every doubtful claim for the teacher. Without approved
 * materials the lesson is always marked "AI-written, teacher review required".
 */
public final class LessonScriptWriter {

    private static final Logger logger = LoggerFactory.getLogger(LessonScriptWriter.class);

    /** The written script, the reviewer's verdict and the claims a teacher must check. */
    public record Result(String script, String draft, List<String> doubtfulClaims, List<String> corrections, boolean grounded) {
        public boolean reviewRequired() {
            return !grounded || !doubtfulClaims.isEmpty();
        }
    }

    public interface Model {
        String generate(String system, String user) throws IOException;
    }

    private final Model model;

    public LessonScriptWriter(OllamaClient ollama) {
        this(ollama::generate);
    }

    LessonScriptWriter(Model model) {
        this.model = model;
    }

    public Result write(TopicRequest request, String materials) throws IOException {
        boolean grounded = materials != null && !materials.isBlank();
        String draft = clean(model.generate(writerPrompt(request, grounded), userPrompt(request, materials)));
        if (words(draft) < Math.min(60, request.targetWords() / 3)) {
            throw new IOException("Lesson script for '" + request.topic() + "' came back empty or too short (" + words(draft) + " words)");
        }
        logger.info("Lesson script drafted: {} words for '{}' ({})", words(draft), request.topic(), request.level());

        String answer = model.generate(reviewerPrompt(request, grounded),
            "TOPIC: " + request.topic() + "\nAUDIENCE: " + request.level() + "\n"
                + (grounded ? "APPROVED MATERIALS:\n" + materials + "\n" : "")
                + "SCRIPT TO REVIEW:\n" + draft);
        JsonObject review = parseJson(answer);
        String corrected = clean(text(review, "corrected_script"));
        List<String> doubtful = list(review, "doubtful_claims");
        List<String> corrections = list(review, "corrections");
        if (words(corrected) < words(draft) / 2) {
            logger.warn("Fact review returned no usable corrected script; keeping the draft and marking it for teacher review");
            corrected = draft;
            if (doubtful.isEmpty()) doubtful = List.of("The automatic fact review did not complete; check every fact in this script.");
        }
        logger.info("Fact review: {} correction(s), {} doubtful claim(s)", corrections.size(), doubtful.size());
        return new Result(corrected, draft, doubtful, corrections, grounded);
    }

    static String writerPrompt(TopicRequest request, boolean grounded) {
        return "You are an experienced " + (request.subject().isBlank() ? "" : request.subject() + " ") + "teacher recording a short "
            + "video lesson for " + request.level() + " students. Write the exact words you will SAY, in " + request.languageName() + ".\n"
            + "Rules:\n"
            + "- About " + request.targetWords() + " words (" + request.minutes() + " minutes of speech). Plain spoken sentences, "
            + "one idea per sentence, 2-6 short paragraphs.\n"
            + "- Structure like a good class: a one-sentence hook, the key idea and its definition, how it works step by step, one "
            + "concrete real-world example, the formula or law if the topic has one (write it in plain text such as v = u + a t, "
            + "then say what each symbol means), and a two-sentence recap.\n"
            + "- Stay strictly inside the syllabus level; no topics the student has not met at this level.\n"
            + (grounded ? "- Use ONLY facts, numbers and examples from the approved materials; never add anything they do not contain.\n"
                        : "- Use only well-established textbook facts; never invent numbers, dates, names or statistics.\n")
            + "- No headings, bullet points, markdown, emojis, LaTeX, stage directions or greetings like 'welcome to my channel'.";
    }

    static String reviewerPrompt(TopicRequest request, boolean grounded) {
        return "You are a strict subject-matter reviewer for " + request.level() + " " + request.subject() + " lessons. You did not write "
            + "the script below. Check every definition, fact, number, unit, formula and example against "
            + (grounded ? "the approved materials (they are the only allowed source)" : "standard textbook knowledge at this level")
            + ". Correct errors in place, delete any sentence you cannot confirm, keep everything else word for word, keep the "
            + "language and the length. Return JSON: {\"corrected_script\": \"...\", \"corrections\": [\"what you changed and why\"], "
            + "\"doubtful_claims\": [\"claims a teacher should double-check, quoted\"]}. Use empty lists when there is nothing.";
    }

    static String userPrompt(TopicRequest request, String materials) {
        return "TOPIC: " + request.topic() + "\nAUDIENCE: " + request.level()
            + (materials == null || materials.isBlank() ? "" : "\nAPPROVED MATERIALS:\n" + materials);
    }

    /** Spoken text only: no markdown, bullet marks, headings or wrapping quotes. */
    static String clean(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) value = value.replaceAll("^```\\w*\\s*|```\\s*$", "");
        return value.replaceAll("(?m)^\\s*#+\\s*.*$", "")
            .replaceAll("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+", "")
            .replaceAll("\\*\\*?([^*]+)\\*\\*?", "$1")
            .replaceAll("(?m)^\\s*(?:narrator|teacher|script)\\s*:\\s*", "")
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    static int words(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private static JsonObject parseJson(String answer) {
        if (answer == null) return new JsonObject();
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) return new JsonObject();
        try {
            return JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static List<String> list(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        JsonElement value = json.get(key);
        if (value instanceof JsonArray array) {
            for (JsonElement item : array) {
                String entry = item.isJsonPrimitive() ? item.getAsString() : item.toString();
                if (!entry.isBlank()) out.add(entry.trim());
            }
        }
        return out;
    }
}
