package com.video.transcribe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One place that knows the languages the pipeline can read and write: their scripts, sentence terminators and narration voices.
 * Everything else (ASR hints, storyboard instructions, translation checks, TTS voice choice) asks this class instead of testing
 * hand-written Unicode ranges, so adding a language is one entry here.
 *
 * Voice ids are the default provider's ({@code edge}); they are suggestions that the video generator maps to whatever
 * text-to-speech engine is configured.
 */
public final class LanguageSupport {

    public record Language(String code, String name, String nativeName, Character.UnicodeScript script, String iso15924,
                           String terminators, List<String> voices, boolean rtl) {
        public String bcp47() {
            return code + "-IN";
        }

        /** The first (preferred) narration voice, or an empty string when none is known. */
        public String voice() {
            return voices.isEmpty() ? "" : voices.get(0);
        }
    }

    private static final Map<String, Language> BY_CODE = new LinkedHashMap<>();

    private static void add(String code, String name, String nativeName, Character.UnicodeScript script, String iso, String terminators,
                            boolean rtl, String... voices) {
        BY_CODE.put(code, new Language(code, name, nativeName, script, iso, terminators, List.of(voices), rtl));
    }

    static {
        add("en", "English", "English", Character.UnicodeScript.LATIN, "Latn", ".!?", false, "en-IN-NeerjaNeural", "en-IN-PrabhatNeural");
        add("hi", "Hindi", "हिन्दी", Character.UnicodeScript.DEVANAGARI, "Deva", ".!?।", false, "hi-IN-SwaraNeural", "hi-IN-MadhurNeural");
        add("mr", "Marathi", "मराठी", Character.UnicodeScript.DEVANAGARI, "Deva", ".!?।", false, "mr-IN-AarohiNeural", "mr-IN-ManoharNeural");
        add("ta", "Tamil", "தமிழ்", Character.UnicodeScript.TAMIL, "Taml", ".!?", false, "ta-IN-PallaviNeural", "ta-IN-ValluvarNeural");
        add("te", "Telugu", "తెలుగు", Character.UnicodeScript.TELUGU, "Telu", ".!?", false, "te-IN-ShrutiNeural", "te-IN-MohanNeural");
        add("ml", "Malayalam", "മലയാളം", Character.UnicodeScript.MALAYALAM, "Mlym", ".!?", false, "ml-IN-SobhanaNeural", "ml-IN-MidhunNeural");
        add("kn", "Kannada", "ಕನ್ನಡ", Character.UnicodeScript.KANNADA, "Knda", ".!?", false, "kn-IN-SapnaNeural", "kn-IN-GaganNeural");
        add("bn", "Bengali", "বাংলা", Character.UnicodeScript.BENGALI, "Beng", ".!?।", false, "bn-IN-TanishaaNeural", "bn-IN-BashkarNeural");
        add("gu", "Gujarati", "ગુજરાતી", Character.UnicodeScript.GUJARATI, "Gujr", ".!?", false, "gu-IN-DhwaniNeural", "gu-IN-NiranjanNeural");
        add("pa", "Punjabi", "ਪੰਜਾਬੀ", Character.UnicodeScript.GURMUKHI, "Guru", ".!?।", false);
        add("or", "Odia", "ଓଡ଼ିଆ", Character.UnicodeScript.ORIYA, "Orya", ".!?।", false);
        add("ur", "Urdu", "اردو", Character.UnicodeScript.ARABIC, "Arab", ".!?۔", true, "ur-IN-GulNeural", "ur-IN-SalmanNeural");
    }

    private LanguageSupport() {
    }

    /** Language for an ISO 639-1 / BCP-47 code ("ta", "ta-IN", "TA"); empty when unsupported. */
    public static Optional<Language> byCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String primary = code.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return Optional.ofNullable(BY_CODE.get(primary));
    }

    /** Language whose name is given in English or in its own script ("Tamil", "தமிழ்"), or by code. */
    public static Optional<Language> byNameOrCode(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String needle = value.trim();
        for (Language language : BY_CODE.values()) {
            if (language.name().equalsIgnoreCase(needle) || language.nativeName().equals(needle)) return Optional.of(language);
        }
        return byCode(needle);
    }

    public static List<Language> all() {
        return List.copyOf(BY_CODE.values());
    }

    /**
     * Language of a text, from the script of its letters. Latin text is English; Devanagari defaults to Hindi unless
     * {@code hint} (the ASR language) says otherwise. Mixed text takes the dominant script, ignoring digits and punctuation.
     */
    public static Language detect(String text, String hint) {
        Map<Character.UnicodeScript, Integer> counts = new LinkedHashMap<>();
        if (text != null) {
            text.codePoints().filter(Character::isLetter).forEach(cp -> counts.merge(Character.UnicodeScript.of(cp), 1, Integer::sum));
        }
        Character.UnicodeScript dominant = counts.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(Character.UnicodeScript.LATIN);
        Optional<Language> hinted = byCode(hint).filter(language -> language.script() == dominant);
        if (hinted.isPresent()) return hinted.get();
        for (Language language : BY_CODE.values()) {
            if (language.script() == dominant) return language;
        }
        return BY_CODE.get("en");
    }

    public static Language detect(String text) {
        return detect(text, null);
    }

    /** Share (0..1) of the letters in {@code text} that belong to the language's script; Latin counts for English only. */
    public static double scriptShare(String text, Language language) {
        if (text == null) return 0;
        long letters = text.codePoints().filter(Character::isLetter).count();
        if (letters == 0) return 0;
        long inScript = text.codePoints().filter(Character::isLetter)
            .filter(cp -> Character.UnicodeScript.of(cp) == language.script()).count();
        return (double) inScript / letters;
    }

    /** The instruction appended to prompts so a model writes every free-text field in the source language. */
    public static String writeInstruction(Language language) {
        if ("en".equals(language.code())) {
            return "Write every storyboard field in English. Do not translate English transcript content into another language.";
        }
        return "The source is " + language.name() + " (" + language.nativeName() + "). Write every storyboard field - narration, headings, "
            + "labels, steps and subtitles - in " + language.name() + " using the " + language.name() + " script. Established scientific "
            + "and technical terms may stay in English when the source uses them. Image prompts stay in English.";
    }
}
