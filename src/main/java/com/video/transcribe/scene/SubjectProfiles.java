package com.video.transcribe.scene;

import java.util.Locale;

/**
 * Subject-level pedagogy guidance appended to the visual director's instructions. This is curriculum taxonomy
 * (how a discipline is best shown), never lesson content, so any topic inside a subject works unchanged.
 */
final class SubjectProfiles {
    private SubjectProfiles() {
    }

    private static boolean has(String key, String... needles) {
        for (String needle : needles) {
            if (key.contains(needle)) return true;
        }
        return false;
    }

    static String guidance(String subject) {
        String key = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        String body;
        if (has(key, "bio", "life", "botany", "zoology", "जीव", "உயிர", "జీవ", "জীব", "જીવ", "ಜೀವ", "ജീവ")) {
            body = "Biology: prefer photographs of real organisms, specimens and microscope views; label only structures that are "
                + "clearly visible and named in the narration; use split_screen for comparisons of named organisms or structures; "
                + "use process_steps for mechanisms, cycles and sequences.";
        } else if (has(key, "chem", "रसायन", "வேதி", "రసాయన", "রসায়ন", "રસાયણ", "ರಸಾಯನ", "രസതന്ത്ര")) {
            body = "Chemistry: reactions, equations, laws and electrode/half-reactions are formula shots with exact species, charges, "
                + "coefficients and states, e.g. Cu^{2+}(aq) + 2e^- -> Cu(s); numeric comparisons (conductivities, masses, yields) are "
                + "graph shots; apparatus is a photograph with labels only for parts the narration names (anode, cathode, electrolyte, "
                + "battery); procedures are process_steps.";
        } else if (has(key, "phys", "भौतिक", "இயற்பியல்", "భౌతిక", "পদার্থ", "ભૌતિક", "ಭೌತ", "ഭൗതിക")) {
            body = "Physics: laws and relations are formula shots in SI notation (V = I R, F = m a, 1/R = 1/R1 + 1/R2); stated "
                + "measurements to compare are graph shots; setups and apparatus are photographs with labels for named parts; "
                + "multi-step reasoning is process_steps. Keep units exactly as spoken.";
        } else if (has(key, "math", "algebra", "geometry", "calculus", "गणित", "கணித", "గణిత", "গণিত", "ગણિત", "ಗಣಿತ", "ഗണിത")) {
            body = "Mathematics: every stated equation, identity or derivation step is a formula shot; a derivation becomes ONE formula "
                + "shot per narrated transformation with one formula line per step and an explain_steps sentence for each (never skip a "
                + "step the narration states); worked numeric examples are formula shots; avoid photographs unless a real object is "
                + "being discussed. Every derivation line must be the PREVIOUS line after exactly ONE algebraic operation applied "
                + "to BOTH sides (write both sides in full, never drop a term). When the narration states a condition (positive, "
                + "zero, negative, greater than, less than) write it as a relation such as b^2 - 4ac > 0, never as a bare expression.";
        } else if (key.contains("hindi") || key.contains("tamil") || key.contains("telugu") || key.contains("language")
                || key.contains("literature") || key.contains("grammar")) {
            body = "Language/humanities: keep narration and any labels in the source script; image prompts stay in English and show the "
                + "concrete scene or object being discussed.";
        } else {
            body = "General: prefer photographs of concrete subjects, formula shots for stated equations, graph shots for stated "
                + "numbers, and process_steps for abstract ideas or sequences.";
        }
        return "\nSubject guidance - " + body + "\n";
    }
}
