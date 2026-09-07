package com.video.transcribe;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

/**
 * Exports StoryboardDocument to formatted Word document (.docx)
 * Matches the format of your sample "Types of Pollination.docx"
 */
public class StoryboardDocxExporter {
    
    public void export(StoryboardDocument storyboard, String outputPath) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            
            // Set document margins
            setDocumentMargins(document);
            
            // Title
            addTitle(document, "Storyboard: " + storyboard.getTitle());
            addEmptyLine(document);
            
            // Process each scene
            for (Scene scene : storyboard.getScenes()) {
                addScene(document, scene);
                addEmptyLine(document);
            }
            
            // Save
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                document.write(out);
            }
        }
    }
    
    private void setDocumentMargins(XWPFDocument document) {
        // Set narrow margins for better table display
        CTSectPr sectPr = document.getDocument().getBody().addNewSectPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setLeft(BigInteger.valueOf(720));   // 0.5 inch
        pageMar.setRight(BigInteger.valueOf(720));  // 0.5 inch
        pageMar.setTop(BigInteger.valueOf(720));
        pageMar.setBottom(BigInteger.valueOf(720));
    }
    
    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(18);
        run.setFontFamily("Arial");
        run.setColor("2E5090");
    }
    
    private void addScene(XWPFDocument doc, Scene scene) {
        // Scene Header
        XWPFParagraph sceneHeader = doc.createParagraph();
        sceneHeader.setSpacingBefore(200);
        XWPFRun headerRun = sceneHeader.createRun();
        headerRun.setText("Scene " + scene.getSceneNumber() + ": " + scene.getSceneTitle());
        headerRun.setBold(true);
        headerRun.setFontSize(14);
        headerRun.setFontFamily("Arial");
        headerRun.setColor("1F4788");
        
        // Narration/Audiobook label
        XWPFParagraph narrLabel = doc.createParagraph();
        narrLabel.setSpacingBefore(100);
        XWPFRun narrLabelRun = narrLabel.createRun();
        narrLabelRun.setText("Narration/Audio/Voiceover:");
        narrLabelRun.setBold(true);
        narrLabelRun.setItalic(true);
        narrLabelRun.setFontSize(11);
        narrLabelRun.setFontFamily("Arial");
        
        // Narration text (quoted)
        XWPFParagraph narrText = doc.createParagraph();
        narrText.setIndentationLeft(400);
        XWPFRun narrRun = narrText.createRun();
        narrRun.setText("\"" + scene.getNarration() + "\"");
        narrRun.setItalic(true);
        narrRun.setFontSize(10);
        narrRun.setFontFamily("Arial");
        
        // Add table for segments
        if (scene.getSegments() != null && !scene.getSegments().isEmpty()) {
            addSegmentTable(doc, scene.getSegments());
        }
    }
    
    private void addSegmentTable(XWPFDocument doc, List<SceneSegment> segments) {
        // Table header row
        XWPFTable table = doc.createTable();
        table.setWidth("100%");
        
        // Set table width to 100%
        CTTblWidth tblWidth = table.getCTTbl().getTblPr().addNewTblW();
        tblWidth.setType(STTblWidth.PCT);
        tblWidth.setW(BigInteger.valueOf(5000)); // 100% in fiftieths of a percent
        
        // Header row
        XWPFTableRow headerRow = table.getRow(0);
        headerRow.setRepeatHeader(true);
        
        // Style header cells
        String[] headers = {"S.no", "Splitting the Narration (Sentence wise)", "Visual / Animation", "Image Recommendation"};
        int[] widths = {8, 32, 30, 30};
        
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = headerRow.getCell(i) != null ? headerRow.getCell(i) : headerRow.addNewTableCell();
            cell.setText(headers[i]);
            cell.setColor("2E5090");
            
            XWPFParagraph para = cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = para.getRuns().get(0);
            run.setBold(true);
            run.setColor("FFFFFF");
            run.setFontSize(10);
            run.setFontFamily("Arial");
        }
        
        // Data rows
        for (SceneSegment segment : segments) {
            XWPFTableRow row = table.createRow();
            
            // S.no
            XWPFTableCell cell1 = row.getCell(0);
            cell1.setText(String.valueOf(segment.getSegmentNumber()));
            styleCell(cell1, ParagraphAlignment.CENTER);
            
            // Sentence
            XWPFTableCell cell2 = row.getCell(1);
            cell2.setText("\"" + segment.getSentence() + "\"");
            styleCell(cell2, ParagraphAlignment.LEFT);
            
            // Visual/Animation
            XWPFTableCell cell3 = row.getCell(2);
            cell3.setText(segment.getVisualAnimation());
            styleCell(cell3, ParagraphAlignment.LEFT);
            
            // Image Recommendations
            XWPFTableCell cell4 = row.getCell(3);
            StringBuilder imgText = new StringBuilder();
            if (segment.getImageRecommendations() != null) {
                for (String img : segment.getImageRecommendations()) {
                    imgText.append(img).append("\n");
                }
            }
            cell4.setText(imgText.toString().trim());
            styleCell(cell4, ParagraphAlignment.LEFT);
        }
        
        // Add spacing after table
        addEmptyLine(doc);
    }
    
    private void styleCell(XWPFTableCell cell, ParagraphAlignment alignment) {
        XWPFParagraph para = cell.getParagraphs().get(0);
        para.setAlignment(alignment);
        if (para.getRuns().isEmpty()) {
            para.createRun();
        }
        XWPFRun run = para.getRuns().get(0);
        run.setFontSize(9);
        run.setFontFamily("Arial");
        
        // Add borders
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    }
    
    private void addEmptyLine(XWPFDocument doc) {
        XWPFParagraph empty = doc.createParagraph();
        empty.setSpacingAfter(100);
    }
}