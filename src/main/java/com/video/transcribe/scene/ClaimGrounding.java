package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.video.transcribe.LanguageSupport;

/**
 * Traces every shot's narration back to its sources (the lecture transcript, the paraphrase made from it, and any approved
 * reference materials). It is a deterministic safety net, not a fact-checker: it cannot know whether a source is right, but
 * it does find claims that appear in no source - most importantly numbers - so a person reviews exactly those shots.
 *
 * Statuses: SUPPORTED, PARTLY_SUPPORTED, UNSUPPORTED, and NOT_CHECKED for titles and very short lines.
 */
public final class ClaimGrounding {
    public enum Status { SUPPORTED, PARTLY_SUPPORTED, UNSUPPORTED, NOT_CHECKED }

    public record Finding(String shotId, Status status, double support, List<String> missingNumbers, List<String> unseenTerms,
                          boolean crossLanguage) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("shot_id", shotId);
            map.put("status", status.name());
            map.put("support", Math.round(support * 100) / 100.0);
            if (!missingNumbers.isEmpty()) map.put("numbers_not_in_source", missingNumbers);
            if (!unseenTerms.isEmpty()) map.put("terms_not_in_source", unseenTerms);
            if (crossLanguage) map.put("note", "narration language differs from the source; only numbers were checked");
            return map;
        }
    }

    public record Report(List<Finding> findings) {
        public long count(Status status) {
            return findings.stream().filter(f -> f.status() == status).count();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("checked", findings.size() - count(Status.NOT_CHECKED));
            map.put("supported", count(Status.SUPPORTED));
            map.put("partly_supported", count(Status.PARTLY_SUPPORTED));
            map.put("unsupported", count(Status.UNSUPPORTED));
            map.put("note", "Automatic source tracing, not fact-checking: it finds claims that no source contains. A subject expert must still review the shots listed here.");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Finding finding : findings) {
                if (finding.status() != Status.SUPPORTED && finding.status() != Status.NOT_CHECKED) list.add(finding.toMap());
            }
            map.put("needs_review", list);
            return map;
        }
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?(?!\\d)|\\d+(?:[.,]\\d+)?");

    /** "1,00,000" and "1,000" are grouped thousands (value 100000 / 1000); "0,5" is a decimal comma. */
    static String normaliseNumber(String raw) {
        return raw.matches("\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?") ? raw.replace(",", "") : raw.replace(',', '.');
    }
    private static final double SUPPORTED_AT = 0.55;
    private static final double PARTLY_AT = 0.35;
    /** Only unambiguous number words: "one", "half", "twice" are ordinary words and would raise false alarms. */
    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
        Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("five", "5"), Map.entry("six", "6"),
        Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"), Map.entry("ten", "10"), Map.entry("eleven", "11"),
        Map.entry("twelve", "12"), Map.entry("twenty", "20"), Map.entry("thirty", "30"), Map.entry("fifty", "50"),
        Map.entry("hundred", "100"), Map.entry("thousand", "1000"));

    private ClaimGrounding() {
    }

    /** Checks every shot against the combined source text and returns a report; narration is never changed. */
    public static Report check(StoryboardDocument storyboard, String sourceText) {
        return check(storyboard, sourceText, false);
    }

    /**
     * @param translated true when the storyboard is a translation of the source: the comparison is then by numbers only, even when both
     *                   languages share a script (Hindi and Marathi are both Devanagari, so the script alone cannot tell).
     */
    public static Report check(StoryboardDocument storyboard, String sourceText, boolean translated) {
        String source = sourceText == null ? "" : sourceText;
        Set<String> sourceStems = stems(source);
        Set<String> sourceNumbers = numbers(source);
        LanguageSupport.Language sourceLanguage = LanguageSupport.detect(source);
        List<Finding> findings = new ArrayList<>();
        if (storyboard == null || storyboard.getScenes() == null) return new Report(findings);
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                findings.add(checkOne(scene.getSceneNumber() + "." + segment.getSegmentNumber(), segment, source, sourceStems,
                    sourceNumbers, sourceLanguage, translated));
            }
        }
        return new Report(findings);
    }

    /** Adds a review note to every shot that needs a person's attention. */
    public static void annotate(StoryboardDocument storyboard, Report report) {
        Map<String, Finding> byId = new LinkedHashMap<>();
        report.findings().forEach(finding -> byId.put(finding.shotId(), finding));
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                Finding finding = byId.get(scene.getSceneNumber() + "." + segment.getSegmentNumber());
                if (finding == null || finding.status() == Status.SUPPORTED || finding.status() == Status.NOT_CHECKED) continue;
                String note = "FACT CHECK (" + finding.status().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "): "
                    + (finding.crossLanguage() ? "" : Math.round(finding.support() * 100) + "% of key terms found in the sources")
                    + (finding.missingNumbers().isEmpty() ? "" : "; numbers not in any source: " + String.join(", ", finding.missingNumbers()))
                    + ". Subject-expert review required.";
                String existing = segment.getCoverageNotes();
                segment.setCoverageNotes(existing == null || existing.isBlank() ? note : existing + "; " + note);
            }
        }
    }

    private static Finding checkOne(String id, SceneSegment segment, String source, Set<String> sourceStems, Set<String> sourceNumbers,
                                    LanguageSupport.Language sourceLanguage, boolean translated) {
        String narration = segment.getSentence() == null ? "" : segment.getSentence();
        if ("title_card".equals(segment.getVisualType())) {
            return new Finding(id, Status.NOT_CHECKED, 1, List.of(), List.of(), false);
        }
        LanguageSupport.Language narrationLanguage = LanguageSupport.detect(narration);
        boolean cross = translated || narrationLanguage.script() != sourceLanguage.script();
        List<String> missingNumbers = new ArrayList<>();
        for (String number : numbers(narration)) {
            if (!sourceNumbers.contains(number)) missingNumbers.add(number);
        }
        if (cross) {
            return new Finding(id, missingNumbers.isEmpty() ? Status.SUPPORTED : Status.UNSUPPORTED, 1, missingNumbers, List.of(), true);
        }
        Set<String> narrationStems = stems(narration);
        if (narrationStems.size() < 3 && missingNumbers.isEmpty()) {
            return new Finding(id, Status.NOT_CHECKED, 1, List.of(), List.of(), false);
        }
        List<String> unseen = new ArrayList<>();
        int found = 0;
        for (String stem : narrationStems) {
            if (sourceStems.contains(stem)) found++;
            else unseen.add(stem);
        }
        double support = narrationStems.isEmpty() ? 1 : (double) found / narrationStems.size();
        Status status = !missingNumbers.isEmpty() || support < PARTLY_AT ? Status.UNSUPPORTED
            : support < SUPPORTED_AT ? Status.PARTLY_SUPPORTED : Status.SUPPORTED;
        return new Finding(id, status, support, missingNumbers, unseen.size() > 6 ? unseen.subList(0, 6) : unseen, false);
    }

    /** Content-word stems: letters only, lowercased, prefix-truncated so inflections ("deposited"/"deposits") still match. */
    static Set<String> stems(String text) {
        Set<String> stems = new HashSet<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().noneMatch(Character::isLetter)) continue;
            boolean latin = word.codePoints().filter(Character::isLetter)
                .allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
            int minimum = latin ? 4 : 3;
            int[] points = word.codePoints().toArray();
            if (points.length < minimum) continue;
            // Indic scripts: a syllable is one to three code points, so a 4-code-point prefix is only about two syllables and matches
            // unrelated words (measured: an invented Tamil sentence scored 100%). Six keeps inflected forms together but not strangers.
            int keep = Math.min(points.length, latin ? 5 : 6);
            stems.add(new String(points, 0, keep));
        }
        return stems;
    }

    /** Numbers written as digits only (any script's digits), normalised to ASCII: what a translation must preserve exactly. */
    public static Set<String> digitNumbers(String text) {
        Set<String> numbers = new HashSet<>();
        if (text == null) return numbers;
        StringBuilder ascii = new StringBuilder();
        text.codePoints().forEach(cp -> ascii.append(Character.isDigit(cp) ? (char) ('0' + Character.digit(cp, 10)) : new String(Character.toChars(cp))));
        Matcher matcher = NUMBER.matcher(ascii);
        while (matcher.find()) numbers.add(normaliseNumber(matcher.group()));
        return numbers;
    }

    /** Numbers written with any digits (including Indic digits) plus English number words, normalised to ASCII. */
    static Set<String> numbers(String text) {
        Set<String> numbers = new HashSet<>();
        if (text == null) return numbers;
        StringBuilder ascii = new StringBuilder();
        text.codePoints().forEach(cp -> ascii.append(Character.isDigit(cp) ? (char) ('0' + Character.digit(cp, 10)) : new String(Character.toChars(cp))));
        Matcher matcher = NUMBER.matcher(ascii);
        while (matcher.find()) numbers.add(normaliseNumber(matcher.group()));
        Matcher words = WORD.matcher(ascii.toString().toLowerCase(Locale.ROOT));
        while (words.find()) {
            String value = NUMBER_WORDS.get(words.group());
            if (value != null) numbers.add(value);
        }
        return numbers;
    }
}
