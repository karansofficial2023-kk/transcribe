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
    void exportsLabelsPlacementAndStyleInSeparateColumns() throws Exception {
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
        storyboard.setScenes(List.of(scene));

        Path output = Path.of("target", "storyboard-contract-test.docx");
        new StoryboardDocxExporter("ltx").export(storyboard, output.toString());

        try (XWPFDocument document = new XWPFDocument(new FileInputStream(output.toFile()))) {
            var table = document.getTables().get(0);
            assertEquals(15, table.getRow(0).getTableCells().size());
            assertEquals("Labels", table.getRow(0).getCell(8).getText());
            assertEquals("Label Placement", table.getRow(0).getCell(9).getText());
            assertEquals("Label Style", table.getRow(0).getCell(10).getText());

            String labels = table.getRow(1).getCell(8).getText();
            assertTrue(labels.contains("anther"));
            assertTrue(labels.contains("stigma"));
            assertFalse(labels.contains("yellow"));
            assertFalse(labels.contains("1080p"));

            assertTrue(table.getRow(1).getCell(9).getText().contains("target=(0.35,0.42)"));
            assertTrue(table.getRow(1).getCell(10).getText().contains("yellow arrows"));
        }
    }
}
