package com.video.transcribe.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.LanguageSupport;

/**
 * A request for a new lesson, for example
 * {@code {"topic": "Photosynthesis", "subject": "Biology", "grade": "10", "minutes": 3, "language": "en", "syllabus": "CBSE"}}.
 * Only the topic is required. Every field is checked: a wrong value stops the item with a message that lists the accepted values
 * (a typo must never silently produce a lesson for the wrong class, language or board). A plain {@code .topic} file may hold
 * "key: value" lines, or just the topic on its first line.
 */
public record TopicRequest(String topic, String subject, String grade, int minutes, String language, String syllabus) {

    public static final int DEFAULT_MINUTES = 3;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 15;
    public static final List<String> FIELDS = List.of("topic", "subject", "grade", "minutes", "language", "syllabus");

    /** Accepted subjects (canonical name -> spellings people use). */
    public static final Map<String, List<String>> SUBJECTS = new LinkedHashMap<>();
    /** Accepted boards / syllabi / exams. */
    public static final Map<String, List<String>> SYLLABI = new LinkedHashMap<>();
    /** Grades: 1-12 or one of these. */
    public static final List<String> GRADE_WORDS = List.of("primary", "middle school", "high school", "higher secondary", "ug", "pg", "college");
    /** Narration languages: the ones with a narration voice in the video generator. */
    public static final List<String> LANGUAGES = List.of("en", "hi", "mr", "ta", "te", "ml", "kn", "bn", "gu", "ur");

    static {
        SUBJECTS.put("Mathematics", List.of("mathematics", "maths", "math"));
        SUBJECTS.put("Physics", List.of("physics"));
        SUBJECTS.put("Chemistry", List.of("chemistry", "chem"));
        SUBJECTS.put("Biology", List.of("biology", "bio", "botany", "zoology"));
        SUBJECTS.put("Science", List.of("science", "general science", "evs", "environmental science"));
        SUBJECTS.put("Computer Science", List.of("computer science", "computers", "cs", "informatics practices", "ip"));
        SUBJECTS.put("Social Science", List.of("social science", "social studies", "sst"));
        SUBJECTS.put("History", List.of("history"));
        SUBJECTS.put("Geography", List.of("geography"));
        SUBJECTS.put("Civics", List.of("civics", "political science"));
        SUBJECTS.put("Economics", List.of("economics"));
        SUBJECTS.put("Accountancy", List.of("accountancy", "accounts"));
        SUBJECTS.put("Business Studies", List.of("business studies"));
        SUBJECTS.put("English", List.of("english"));
        SUBJECTS.put("General", List.of("general", "other"));

        SYLLABI.put("CBSE", List.of("cbse"));
        SYLLABI.put("NCERT", List.of("ncert"));
        SYLLABI.put("ICSE", List.of("icse", "cisce"));
        SYLLABI.put("ISC", List.of("isc"));
        SYLLABI.put("State Board", List.of("state board", "state", "stateboard", "samacheer", "samacheer kalvi"));
        SYLLABI.put("NEET", List.of("neet"));
        SYLLABI.put("JEE", List.of("jee", "jee main", "jee advanced", "iit jee"));
        SYLLABI.put("IB", List.of("ib", "international baccalaureate"));
        SYLLABI.put("IGCSE", List.of("igcse", "cambridge", "cambridge igcse"));
        SYLLABI.put("General", List.of("general", "none", "other"));
    }

    /** Narration length for the requested minutes at a calm teaching pace (about 130 spoken words a minute). */
    public int targetWords() {
        return minutes * 130;
    }

    /** "Class 10 Biology (CBSE)" style description of the audience. */
    public String level() {
        String level = grade.isBlank() ? "high school" : (grade.matches("\\d+") ? "Class " + grade : grade);
        if (!subject.isBlank() && !"General".equals(subject)) level += " " + subject;
        if (!syllabus.isBlank() && !"General".equals(syllabus)) level += " (" + syllabus + ")";
        return level;
    }

    public String languageName() {
        return LanguageSupport.byCode(language).map(LanguageSupport.Language::name).orElse("English");
    }

    /** The accepted values, for the error messages, the console help and the documentation. */
    public static String acceptedValues() {
        return "topic: required, 3-200 characters\n"
            + "subject: " + String.join(", ", SUBJECTS.keySet()) + "\n"
            + "grade: 1-12 or " + String.join(", ", GRADE_WORDS) + "\n"
            + "minutes: " + MIN_MINUTES + "-" + MAX_MINUTES + " (default " + DEFAULT_MINUTES + ")\n"
            + "language: " + String.join(", ", LANGUAGES.stream().map(code -> code + " ("
                + LanguageSupport.byCode(code).map(LanguageSupport.Language::name).orElse(code) + ")").toList()) + " (default en)\n"
            + "syllabus: " + String.join(", ", SYLLABI.keySet());
    }

    public static TopicRequest read(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("﻿", "").trim();
        return parse(text, file.getFileName().toString());
    }

    static TopicRequest parse(String text, String fileName) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text.startsWith("{")) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    String value = entry.getValue().isJsonNull() ? "" : entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString() : entry.getValue().toString();
                    fields.put(entry.getKey().trim().toLowerCase(Locale.ROOT), value.trim());
                }
            } catch (RuntimeException e) {
                throw invalid(fileName, List.of("not valid JSON (" + e.getMessage() + ")"));
            }
        } else {
            String[] lines = text.split("\\R");
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().matches("[A-Za-z_ ]{2,20}")) {
                    fields.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            if (!fields.containsKey("topic") && lines.length > 0 && !lines[0].contains(":")) fields.put("topic", lines[0].trim());
        }

        List<String> problems = new ArrayList<>();
        for (String key : fields.keySet()) {
            if (!FIELDS.contains(key)) problems.add("unknown field '" + key + "' (allowed: " + String.join(", ", FIELDS) + ")");
        }
        String topic = fields.getOrDefault("topic", "").trim();
        if (topic.length() < 3 || topic.length() > 200) problems.add("topic must be 3-200 characters");

        String subject = canonical(fields.get("subject"), SUBJECTS).orElse(null);
        if (subject == null) problems.add("subject '" + fields.get("subject") + "' is not one of: " + String.join(", ", SUBJECTS.keySet()));

        String syllabus = canonical(fields.get("syllabus"), SYLLABI).orElse(null);
        if (syllabus == null) problems.add("syllabus '" + fields.get("syllabus") + "' is not one of: " + String.join(", ", SYLLABI.keySet()));

        String grade = fields.getOrDefault("grade", "").trim().replaceFirst("(?i)^(class|grade|std\\.?|standard)\\s*", "");
        boolean gradeOk = grade.isEmpty() || (grade.matches("\\d{1,2}") && Integer.parseInt(grade) >= 1 && Integer.parseInt(grade) <= 12)
            || GRADE_WORDS.contains(grade.toLowerCase(Locale.ROOT));
        if (!gradeOk) problems.add("grade '" + fields.get("grade") + "' must be 1-12 or one of: " + String.join(", ", GRADE_WORDS));

        int minutes = DEFAULT_MINUTES;
        String rawMinutes = fields.getOrDefault("minutes", "").trim();
        if (!rawMinutes.isEmpty()) {
            try {
                double value = Double.parseDouble(rawMinutes);
                if (value < MIN_MINUTES || value > MAX_MINUTES || value != Math.rint(value)) throw new NumberFormatException();
                minutes = (int) value;
            } catch (NumberFormatException e) {
                problems.add("minutes '" + rawMinutes + "' must be a whole number from " + MIN_MINUTES + " to " + MAX_MINUTES);
            }
        }

        String language = "en";
        String rawLanguage = fields.getOrDefault("language", "").trim();
        if (!rawLanguage.isEmpty()) {
            Optional<LanguageSupport.Language> found = LanguageSupport.byNameOrCode(rawLanguage);
            if (found.isEmpty() || !LANGUAGES.contains(found.get().code())) {
                problems.add("language '" + rawLanguage + "' is not one of: " + String.join(", ", LANGUAGES));
            } else {
                language = found.get().code();
            }
        }

        if (!problems.isEmpty()) throw invalid(fileName, problems);
        return new TopicRequest(topic, subject, grade.toLowerCase(Locale.ROOT).matches("\\d+|") ? grade : grade.toLowerCase(Locale.ROOT),
            minutes, language, syllabus);
    }

    /** Blank -> "" (not given); a known spelling -> its canonical name; anything else -> empty (invalid). */
    private static Optional<String> canonical(String value, Map<String, List<String>> table) {
        if (value == null || value.isBlank()) return Optional.of("");
        String wanted = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return table.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(wanted) || e.getValue().contains(wanted))
            .map(Map.Entry::getKey).findFirst();
    }

    private static IOException invalid(String fileName, List<String> problems) {
        return new IOException("Topic file invalid: " + fileName + "\n  - " + String.join("\n  - ", problems)
            + "\nAccepted values:\n" + acceptedValues());
    }
}
