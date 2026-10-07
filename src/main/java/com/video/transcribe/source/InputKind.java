package com.video.transcribe.source;

import java.util.Locale;

/**
 * What a file in the input folder is, from its name. All three kinds share the same queue, one item at a time:
 * <ul>
 * <li>VIDEO: a recorded lesson (audio -> speech recognition -> narration -> storyboard), the original route;</li>
 * <li>TEXT: a transcript or teacher notes as .txt, .docx or .pdf (starts at the narration step);</li>
 * <li>TOPIC: {@code <name>.topic.json} (or {@code <name>.topic}) asking for a new lesson on a topic: the language model writes the
 * script, an independent review checks it, then the same narration and storyboard steps follow.</li>
 * </ul>
 */
public enum InputKind {
    VIDEO, TEXT, TOPIC;

    /** The kind of a file name, or null when the file is not a lesson input (office lock files and hidden files included). */
    public static InputKind of(String fileName) {
        if (fileName == null) return null;
        String name = fileName.toLowerCase(Locale.ROOT).trim();
        if (name.startsWith("~$") || name.startsWith(".") || name.equals("desktop.ini")) return null;
        if (name.endsWith(".topic.json") || name.endsWith(".topic")) return TOPIC;
        if (name.matches(".*\\.(mp4|mov|avi|mkv|wmv|webm|flv)$")) return VIDEO;
        if (name.matches(".*\\.(txt|docx|pdf)$")) return TEXT;
        return null;
    }

    /** "Photosynthesis.topic.json" -> "Photosynthesis", "notes.docx" -> "notes". */
    public static String baseName(String fileName) {
        String name = fileName == null ? "" : fileName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".topic.json")) return name.substring(0, name.length() - ".topic.json".length());
        if (lower.endsWith(".topic")) return name.substring(0, name.length() - ".topic".length());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
