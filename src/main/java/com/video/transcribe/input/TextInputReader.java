package com.video.transcribe.input;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Plain text of a transcript or teacher notes given as .txt, .docx or .pdf, cleaned into narration paragraphs. */
public final class TextInputReader {

    /** Fewer words than this is not a lesson (a title, an empty export, or a scanned PDF without a text layer). */
    static final int MIN_WORDS = 25;

    private TextInputReader() {
    }

    public static String read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String raw;
        if (name.endsWith(".docx")) {
            raw = docx(file);
        } else if (name.endsWith(".pdf")) {
            raw = pdf(file);
        } else {
            raw = txt(file);
        }
        String text = clean(raw);
        int words = text.isBlank() ? 0 : text.split("\\s+").length;
        if (words < MIN_WORDS) {
            throw new IOException("Input text is empty or too short (" + words + " words) in " + file.getFileName()
                + (name.endsWith(".pdf") ? "; a scanned PDF has no text layer and needs OCR first" : ""));
        }
        return text;
    }

    static String txt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1252"));          // Notepad's older "ANSI" files
        }
    }

    static String docx(Path file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    text.append(paragraph.getText()).append('\n');
                } else if (element instanceof XWPFTable table) {
                    table.getRows().forEach(row -> row.getTableCells().forEach(cell -> text.append(cell.getText()).append('\n')));
                }
            }
        }
        return text.toString();
    }

    static String pdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * One paragraph per blank-line-separated block; lines inside a block are joined (PDF and copied text break lines mid-sentence),
     * hyphenated line ends are rejoined, bullet marks and timestamps ("00:01:02", "[00:01]") are removed.
     */
    static String clean(String raw) {
        String text = (raw == null ? "" : raw).replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[\\u00A0\\t]+", " ")
            // line-start patterns use [ \t]* (not \s*): \s would swallow the blank line that separates two paragraphs
            .replaceAll("(?m)^[ \\t]*\\[?\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d+)?\\]?[ \\t]*(?:-->[ \\t]*\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d+)?)?[ \\t]*", "")
            .replaceAll("(?m)^[ \\t]*\\d+[ \\t]*$", "")                     // SRT cue numbers
            .replaceAll("(?m)^[ \\t]*[•▪●◦\\-*][ \\t]+", "")
            .replaceAll("(\\p{L})-\\n(\\p{L})", "$1$2");
        StringBuilder out = new StringBuilder();
        for (String block : text.split("\\n\\s*\\n")) {
            String paragraph = block.replaceAll("\\s*\\n\\s*", " ").replaceAll(" {2,}", " ").trim();
            if (!paragraph.isEmpty()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(paragraph);
            }
        }
        return out.toString();
    }
}
