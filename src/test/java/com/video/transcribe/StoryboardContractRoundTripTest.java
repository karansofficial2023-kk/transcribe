package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardContractAudit;
import com.video.transcribe.scene.StoryboardDocument;

class StoryboardContractRoundTripTest {

    private static SceneSegment labelled() {
        SceneSegment s = new SceneSegment();
        s.setSegmentNumber(1);
        s.setSentence("The resistor limits the current in the circuit.");
        s.setVisualType("realistic_labeled_image");
        s.setVisualSubject("Bench circuit with a battery, a resistor and a bulb");
        s.setComfyPrompt("Photo of a simple circuit on a bench, text-free");
        s.setLabels(List.of("Resistor", "Battery"));
        s.setLabelPlacements(List.of("Resistor | banded cylinder on the wire", "Battery | cylindrical cell at left"));
        s.setLabelStyle("high_contrast_box");
        s.setMotion("camera: slow_zoom_in");
        s.setSubtitle("The resistor limits the current.");
        s.setSubtitleStyle("bottom_band; max_lines=2");
        s.setRecommendedClipSeconds(8.2);
        s.setFormulaLines(List.of("V = I R", "The voltage across the resistor equals current times resistance."));
        s.setSteps(List.of("Measure current", "Measure voltage"));
        s.setCoverageNotes("Coverage ok");
        return s;
    }

    private static StoryboardDocument document(SceneSegment segment) {
        Scene scene = new Scene();
        scene.setSceneNumber(2);
        scene.setSceneTitle("Circuits");
        scene.setNarration(segment.getSentence());
        scene.setSegments(List.of(segment));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Simple Circuits");
        doc.setScenes(List.of(scene));
        return doc;
    }

    @Test
    void docxAndJsonContractRoundTripAreEqualForEveryField(@TempDir Path dir) throws Exception {
        StoryboardDocument doc = document(labelled());
        Path docx = dir.resolve("lesson_storyboard.docx");
        new StoryboardDocxExporter("ltx").export(doc, docx.toString());

        Path json = dir.resolve("lesson_contract.json");
        assertTrue(Files.isRegularFile(json));
        Map<String, Object> contract = new Gson().fromJson(Files.readString(json),
            new TypeToken<Map<String, Object>>() { }.getType());
        assertEquals("2.0", contract.get("schema_version"));
        Map<?, ?> language = (Map<?, ?>) contract.get("language");
        assertEquals("en-IN", language.get("bcp47"));
        assertEquals("ltr", language.get("direction"));
        Map<?, ?> scene = (Map<?, ?>) ((List<?>) contract.get("scenes")).get(0);
        assertEquals(2.0, ((Number) scene.get("scene_number")).doubleValue());
        Map<?, ?> shot = (Map<?, ?>) ((List<?>) scene.get("shots")).get(0);

        Map<String, String> fromDocx = new java.util.LinkedHashMap<>();
        try (XWPFDocument d = new XWPFDocument(new FileInputStream(docx.toFile()))) {
            XWPFTable table = d.getTables().stream()
                .filter(t -> "shot_id".equals(t.getRow(0).getCell(0).getText())).findFirst().orElseThrow();
            table.getRows().forEach(r -> fromDocx.put(r.getCell(0).getText(), r.getCell(1).getText()));
        }

        for (Object key : shot.keySet()) {
            String field = (String) key;
            Object expected = shot.get(field);
            String cell = fromDocx.get(field);
            if (expected instanceof List<?> items) {
                List<String> actual = cell == null || cell.isBlank() ? List.of()
                    : List.of(cell.split("\\R"));
                assertEquals(items.stream().map(String::valueOf).toList(), actual, field);
            } else if (expected instanceof Number number) {
                assertEquals(number.doubleValue(), Double.parseDouble(cell.replace(" sec", "")), 1e-9, field);
            } else {
                assertEquals(String.valueOf(expected), cell == null ? "" : cell.trim(), field);
            }
        }
        // Prose was moved out of formula_lines; the equation stays; review_notes no longer hides fields.
        assertEquals(List.of("V = I R"), shot.get("formula_lines"));
        assertEquals(List.of("The voltage across the resistor equals current times resistance."),
            shot.get("explain_steps"));
        assertEquals("Coverage ok", shot.get("review_notes"));
        assertTrue(fromDocx.containsKey("formula_lines") && fromDocx.containsKey("explain_steps"));
        assertTrue(Files.isRegularFile(dir.resolve("lesson_audit.json")));
    }

    @Test
    void emptyOptionalListFieldsAreOmittedFromDocxButPresentInJson(@TempDir Path dir) throws Exception {
        SceneSegment s = labelled();
        s.setFormulaLines(List.of());
        s.setSteps(List.of());
        StoryboardDocument doc = document(s);
        Path docx = dir.resolve("plain_storyboard.docx");
        new StoryboardDocxExporter().export(doc, docx.toString());
        try (XWPFDocument d = new XWPFDocument(new FileInputStream(docx.toFile()))) {
            XWPFTable table = d.getTables().get(0);
            assertFalse(table.getRows().stream().anyMatch(r -> "formula_lines".equals(r.getCell(0).getText())));
        }
        String json = Files.readString(dir.resolve("plain_contract.json"));
        assertTrue(json.contains("\"formula_lines\": []"));
    }

    @Test
    void labelContractFlagsCountMismatchAbstractTargetAndBadPlacement() {
        SceneSegment s = labelled();
        s.setLabels(List.of("Resistor", "Energy Transfer Process", "Battery"));
        s.setLabelPlacements(List.of("Resistor | banded cylinder",
            "Energy Transfer Process | invisible flow",
            "Wrong start | somewhere"));
        List<StoryboardContractAudit.Issue> issues = StoryboardContractAudit.inspect(document(s));
        assertTrue(issues.stream().anyMatch(i -> "labels".equals(i.field()) && i.reason().contains("abstract")));
        assertTrue(issues.stream().anyMatch(i -> "label_placement".equals(i.field())
            && i.reason().contains("begin with the exact label 'Battery'")));
        assertEquals("2.1", issues.get(0).shotId());

        s.setLabelPlacements(List.of("Resistor | banded cylinder"));
        assertTrue(StoryboardContractAudit.inspect(document(s)).stream()
            .anyMatch(i -> i.reason().contains("3 labels but 1 placements")));
    }

    @Test
    void pendingCoordinatesTokenIsAcceptedAndValidLabelsPass() {
        SceneSegment s = labelled();
        s.setFormulaLines(List.of("V = I R"));
        s.setLabelPlacements(List.of("Resistor | banded cylinder | COORDINATES_PENDING_APPROVED_IMAGE",
            "Battery | COORDINATES_PENDING_APPROVED_IMAGE"));
        assertTrue(StoryboardContractAudit.inspect(document(s)).isEmpty());
    }

    @Test
    void formulaPurityDetectsProseAndBrokenSyntax() {
        assertTrue(StoryboardContractAudit.isProse("The voltage across the resistor equals current times resistance."));
        assertFalse(StoryboardContractAudit.isProse("V = I R"));
        assertFalse(StoryboardContractAudit.isProse("Cu^{2+} + 2e^- -> Cu"));
        assertFalse(StoryboardContractAudit.isProse("\\lambda^0_m = \\nu_+ \\lambda^0_+ + \\nu_- \\lambda^0_-"));
        assertNull(StoryboardContractAudit.syntaxProblem("(a + b)^2 = a^2 + 2ab + b^2"));
        assertNotNull(StoryboardContractAudit.syntaxProblem("(a + b"));
        assertNotNull(StoryboardContractAudit.syntaxProblem("lambda^0_"));
        assertNotNull(StoryboardContractAudit.syntaxProblem("x^)"));

        SceneSegment s = labelled();
        s.setLabels(List.of());
        s.setLabelPlacements(List.of());
        s.setFormulaLines(new ArrayList<>(List.of("x = (a + b", "Add the two sides together to get the sum.")));
        List<StoryboardContractAudit.Issue> issues = StoryboardContractAudit.inspect(document(s));
        assertEquals(2, issues.size());
        assertTrue(issues.stream().allMatch(i -> "formula_lines".equals(i.field()) && "error".equals(i.severity())));
    }

    @Test
    void manifestFingerprintFindsDuplicateOnlyWhenTranscriptExists(@TempDir Path dir) throws Exception {
        Path media = dir.resolve("a.mp4");
        Files.writeString(media, "same bytes");
        Map<String, Object> manifest = JobManifest.build(media, "a.mp4", "en", "no-such-ffprobe");
        assertEquals(64, ((String) manifest.get("sha256")).length());
        assertEquals(-1.0, (double) manifest.get("duration_seconds"));
        JobManifest.write(dir, "a", manifest);
        assertNull(JobManifest.findDuplicate(dir, (String) manifest.get("sha256")));
        Files.writeString(dir.resolve("a_transcript.json"), "{}");
        assertEquals("a", JobManifest.findDuplicate(dir, (String) manifest.get("sha256")));
        assertNull(JobManifest.findDuplicate(dir, "0".repeat(64)));
    }
}
