package com.video.transcribe.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.video.transcribe.source.InputKind;

class LessonInputsTest {

    private static final String LESSON = "Photosynthesis is the process by which green plants make their own food. "
        + "Leaves take in carbon dioxide from the air and water from the soil. Using the energy of sunlight, the chlorophyll in the "
        + "leaf turns these into glucose and releases oxygen.";

    @Test
    void inputKindFromTheFileName() {
        assertEquals(InputKind.VIDEO, InputKind.of("Lesson 1.MP4"));
        assertEquals(InputKind.TEXT, InputKind.of("notes.docx"));
        assertEquals(InputKind.TEXT, InputKind.of("transcript.txt"));
        assertEquals(InputKind.TEXT, InputKind.of("chapter.pdf"));
        assertEquals(InputKind.TOPIC, InputKind.of("Photosynthesis.topic.json"));
        assertEquals(InputKind.TOPIC, InputKind.of("Ohm's law.topic"));
        assertNull(InputKind.of("~$notes.docx"));             // Word's lock file while the document is open
        assertNull(InputKind.of("desktop.ini"));
        assertNull(InputKind.of("picture.png"));
        assertEquals("Photosynthesis", InputKind.baseName("Photosynthesis.topic.json"));
        assertEquals("notes", InputKind.baseName("notes.docx"));
    }

    @Test
    void readsTxtInUtf8AndInOlderWindowsEncoding(@TempDir Path dir) throws IOException {
        Path utf8 = dir.resolve("a.txt");
        Files.writeString(utf8, "﻿" + LESSON, StandardCharsets.UTF_8);
        assertEquals(LESSON, TextInputReader.read(utf8));
        Path ansi = dir.resolve("b.txt");
        Files.write(ansi, (LESSON + " Café notes.").getBytes(Charset.forName("windows-1252")));
        assertTrue(TextInputReader.read(ansi).endsWith("Café notes."));
    }

    @Test
    void readsDocxAndPdf(@TempDir Path dir) throws IOException {
        Path docx = dir.resolve("notes.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            for (String sentence : LESSON.split("(?<=\\.) ")) document.createParagraph().createRun().setText(sentence);
            document.write(out);
        }
        assertTrue(TextInputReader.read(docx).contains("chlorophyll in the leaf"));

        Path pdf = dir.resolve("chapter.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.newLineAtOffset(40, 700);
                for (String sentence : LESSON.split("(?<=\\.) ")) {
                    stream.showText(sentence);
                    stream.newLineAtOffset(0, -16);
                }
                stream.endText();
            }
            document.save(pdf.toFile());
        }
        String text = TextInputReader.read(pdf);
        assertTrue(text.contains("Photosynthesis is the process"), text);
        assertFalse(text.contains("\n"), "lines of one paragraph are joined: " + text);
    }

    @Test
    void anEmptyOrScannedFileIsAClearFinalError(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(empty.toFile());
        }
        IOException error = assertThrows(IOException.class, () -> TextInputReader.read(empty));
        assertTrue(error.getMessage().contains("scanned PDF"), error.getMessage());
        assertTrue(com.video.transcribe.queue.FailureKind.isFinal(error.getMessage()));
    }

    @Test
    void cleansTimestampsBulletsAndBrokenLines() {
        String raw = "1\n00:00:01,000 --> 00:00:04,000\nPhotosynthesis is the pro-\ncess plants use.\n\n- Leaves take in carbon dioxide.";
        assertEquals("Photosynthesis is the process plants use.\n\nLeaves take in carbon dioxide.", TextInputReader.clean(raw));
    }

    @Test
    void aValidTopicRequest() throws IOException {
        TopicRequest request = TopicRequest.parse("{\"topic\": \"Photosynthesis\", \"subject\": \"bio\", \"grade\": \"Class 10\", "
            + "\"minutes\": 4, \"language\": \"Tamil\", \"syllabus\": \"cbse\"}", "p.topic.json");
        assertEquals("Biology", request.subject());
        assertEquals("10", request.grade());
        assertEquals(4, request.minutes());
        assertEquals("ta", request.language());
        assertEquals("CBSE", request.syllabus());
        assertEquals("Class 10 Biology (CBSE)", request.level());
        assertEquals(520, request.targetWords());
        TopicRequest minimal = TopicRequest.parse("Newton's laws of motion", "n.topic");
        assertEquals("en", minimal.language());
        assertEquals(TopicRequest.DEFAULT_MINUTES, minimal.minutes());
    }

    @Test
    void everyWrongFieldIsReportedWithTheAcceptedValues() {
        IOException error = assertThrows(IOException.class, () -> TopicRequest.parse(
            "{\"topic\": \"Atoms\", \"subject\": \"alchemy\", \"grade\": \"14\", \"minutes\": 40, \"language\": \"French\", "
                + "\"syllabus\": \"XYZ\", \"langauge\": \"en\"}", "a.topic.json"));
        String message = error.getMessage();
        for (String expected : List.of("subject 'alchemy'", "grade '14'", "minutes '40'", "language 'French'", "syllabus 'XYZ'",
                "unknown field 'langauge'", "Accepted values:", "Mathematics", "CBSE", "ta (Tamil)")) {
            assertTrue(message.contains(expected), expected + " missing in: " + message);
        }
        assertTrue(com.video.transcribe.queue.FailureKind.isFinal(message));     // retrying cannot fix a typo
        assertThrows(IOException.class, () -> TopicRequest.parse("{\"subject\": \"Physics\"}", "b.topic.json"));
    }

    @Test
    void theScriptIsWrittenThenIndependentlyReviewed() throws IOException {
        List<String> systems = new ArrayList<>();
        String draft = (LESSON + " ").repeat(4).trim();
        LessonScriptWriter writer = new LessonScriptWriter((system, user) -> {
            systems.add(system);
            if (systems.size() == 1) return "## Script\n" + draft;
            return "{\"corrected_script\": " + com.google.gson.JsonParser.parseString("\"" + draft.replace("glucose", "glucose (a sugar)")
                + "\"") + ", \"corrections\": [\"glucose explained\"], \"doubtful_claims\": []}";
        });
        TopicRequest request = TopicRequest.parse("{\"topic\": \"Photosynthesis\", \"subject\": \"Biology\", \"grade\": \"7\"}", "p");
        LessonScriptWriter.Result result = writer.write(request, "");
        assertTrue(result.script().contains("glucose (a sugar)"));
        assertFalse(result.script().contains("##"));
        assertEquals(List.of("glucose explained"), result.corrections());
        assertTrue(result.reviewRequired(), "no approved materials: always a teacher review");
        assertTrue(systems.get(0).contains("Class 7 Biology"));
        assertTrue(systems.get(1).contains("did not write"));        // the reviewer is a separate role
    }

    @Test
    void aFailedReviewKeepsTheDraftButFlagsEverything() throws IOException {
        String draft = (LESSON + " ").repeat(4).trim();
        LessonScriptWriter writer = new LessonScriptWriter((system, user) -> system.contains("did not write") ? "no json" : draft);
        LessonScriptWriter.Result result = writer.write(TopicRequest.parse("Photosynthesis", "p.topic"), "Approved notes ...");
        assertEquals(draft, result.script());
        assertTrue(result.reviewRequired());
        assertFalse(result.doubtfulClaims().isEmpty());
    }
}
