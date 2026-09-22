package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

class StoryboardDocxExporterTest {

    @Test
    void exportsReadableProductionSpecificationWithSeparateLabelFields() throws Exception {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setSentence("Pollen moves from anther to stigma.");
        segment.setTemplate("labeled_image");
        segment.setVisualType("realistic_labeled_image");
        segment.setHeading("Pollen Transfer");
        segment.setLabels(List.of("anther", "stigma", "pollen grains"));
        segment.setLabelPlacements(List.of(
            "anther: box=left; target=(0.35,0.42); target_description=pollen-bearing anther",
            "stigma: box=right; target=(0.58,0.36); target_description=receptive stigma tip",
            "pollen grains: box=bottom; target=(0.43,0.45); target_description=visible pollen grains"));
        segment.setLabelStyle("white label box; dark outline; yellow arrows; readable at 1080p");
        segment.setMotion("overlay_sequence: arrow_draw_then_label_fade");
        segment.setSubtitle("Pollen moves from the anther to the stigma.");

        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSceneTitle("Definition");
        scene.setNarration(segment.getSentence());
        scene.setSegments(List.of(segment));

        StoryboardDocument storyboard = new StoryboardDocument();
        storyboard.setTitle("Types of Pollination");
        storyboard.setSubject("Biology");
        storyboard.setTopic("Pollination in flowering plants");
        storyboard.setSmeRole("Botany curriculum specialist");
        storyboard.setScenes(List.of(scene));

        Path output = Path.of("target", "storyboard-contract-test.docx");
        new StoryboardDocxExporter("ltx").export(storyboard, output.toString());

        try (XWPFDocument document = new XWPFDocument(new FileInputStream(output.toFile()))) {
            assertEquals("Storyboard: Types of Pollination", document.getParagraphs().get(0).getText());
            assertEquals(2, document.getTables().get(0).getRow(0).getTableCells().size());
            assertEquals("Subject", document.getTables().get(0).getRow(0).getCell(0).getText());
            assertEquals("Biology", document.getTables().get(0).getRow(0).getCell(1).getText());

            var table = document.getTables().stream()
                .filter(value -> "Narration".equals(value.getRow(0).getCell(0).getText()))
                .findFirst().orElseThrow();
            assertEquals(2, table.getRow(0).getTableCells().size());
            var labelsRow = table.getRows().stream()
                .filter(row -> "Labels".equals(row.getCell(0).getText())).findFirst().orElseThrow();
            var placementRow = table.getRows().stream()
                .filter(row -> "Label Placement".equals(row.getCell(0).getText())).findFirst().orElseThrow();
            var styleRow = table.getRows().stream()
                .filter(row -> "Label Style".equals(row.getCell(0).getText())).findFirst().orElseThrow();

            String labels = labelsRow.getCell(1).getText();
            assertTrue(labels.contains("anther"));
            assertTrue(labels.contains("stigma"));
            assertFalse(labels.contains("yellow"));
            assertFalse(labels.contains("1080p"));

            assertTrue(placementRow.getCell(1).getText().contains("target=(0.35,0.42)"));
            assertTrue(styleRow.getCell(1).getText().contains("yellow arrows"));
        }
    }
}
