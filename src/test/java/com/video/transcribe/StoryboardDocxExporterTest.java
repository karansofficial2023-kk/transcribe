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
            "anther | pollen-bearing anther | target_xy: AUTO_VERIFY",
            "stigma | receptive stigma tip | target_xy: AUTO_VERIFY",
            "pollen grains | visible pollen grains | target_xy: AUTO_VERIFY"));
        segment.setLabelStyle("high_contrast_box; white_text; dark_background; colored_target_dot; 3px_leader_line; 28px_minimum_font; avoid_subject; avoid_title_area; avoid_subtitle_area; avoid_logo_area");
        segment.setMotion("arrow_draw_then_label_fade; reveal_in_list_order; keep_previous_labels_visible; completed_frame_hold=2.5s");
        segment.setSubtitle("Pollen moves from the anther to the stigma.");
        segment.setSubtitleStyle("bottom_band; band_color=black; band_opacity=0.55; text_color=white; font_size=42; max_lines=2; align=center; horizontal_margin=120; bottom_margin=55");
        segment.setRecommendedClipSeconds(6.0);

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
                .filter(value -> "shot_id".equals(value.getRow(0).getCell(0).getText()))
                .findFirst().orElseThrow();
            assertEquals(2, table.getRow(0).getTableCells().size());
            var labelsRow = table.getRows().stream()
                .filter(row -> "labels".equals(row.getCell(0).getText())).findFirst().orElseThrow();
            var placementRow = table.getRows().stream()
                .filter(row -> "label_placement".equals(row.getCell(0).getText())).findFirst().orElseThrow();
            var styleRow = table.getRows().stream()
                .filter(row -> "label_style".equals(row.getCell(0).getText())).findFirst().orElseThrow();

            String labels = labelsRow.getCell(1).getText();
            assertTrue(labels.contains("anther"));
            assertTrue(labels.contains("stigma"));
            assertFalse(labels.contains("yellow"));
            assertFalse(labels.contains("1080p"));

            assertTrue(placementRow.getCell(1).getText().contains("target_xy: AUTO_VERIFY"));
            assertTrue(styleRow.getCell(1).getText().contains("28px_minimum_font"));
            assertEquals(17, table.getRows().size());
        }
    }
}
