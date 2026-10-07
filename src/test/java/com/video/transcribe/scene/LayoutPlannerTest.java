package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LayoutPlannerTest {

    private static SceneSegment shot(int n, String sentence, List<String> formulas) {
        SceneSegment s = new SceneSegment();
        s.setSegmentNumber(n);
        s.setSentence(sentence);
        s.setFormulaLines(formulas);
        return s;
    }

    private static StoryboardDocument lesson(SceneSegment... shots) {
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSceneTitle("Conductors and Insulators");
        scene.setSegments(new ArrayList<>(List.of(shots)));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Conductance");
        doc.setScenes(new ArrayList<>(List.of(scene)));
        return doc;
    }

    @Test
    void rulesChooseALayoutFromTheNarration() {
        assertEquals("definition", LayoutPlanner.ruleLayout(shot(1, "Substances that allow current to pass are called conductors.", List.of())));
        assertEquals("table", LayoutPlanner.ruleLayout(shot(2, "Metals conduct by electrons whereas electrolytes conduct by ions.", List.of())));
        assertEquals("derivation", LayoutPlanner.ruleLayout(shot(3, "Resistance follows Ohm's law.", List.of("V = IR"))));
        assertEquals("summary", LayoutPlanner.ruleLayout(shot(4, "To summarize, conduction depends on the medium.", List.of())));
        assertEquals("gallery", LayoutPlanner.ruleLayout(shot(5, "It is used in paints, varnishes, perfumes and dyes.", List.of())));
        assertEquals("bullets", LayoutPlanner.ruleLayout(shot(6, "Conduction increases with temperature.", List.of())));
    }

    @Test
    void theModelAddsTableRowsAndATableWithoutRowsFallsBack() {
        SceneSegment compare = shot(1, "Metals conduct by electrons whereas electrolytes conduct by ions.", List.of());
        SceneSegment uses = shot(2, "It is used in paints, varnishes and perfumes.", List.of());
        SceneSegment other = shot(3, "Electrolytes differ in strength.", List.of());
        LayoutPlanner.plan(lesson(compare, uses, other), (system, user) -> "{\"shots\": ["
            + "{\"id\": \"1.1\", \"layout\": \"table\", \"rows\": [\"Metallic conductors | Electrolytic conductors\", \"Electrons | Ions\"]},"
            + "{\"id\": \"1.2\", \"layout\": \"gallery\", \"items\": [\"Paints\", \"Varnishes\", \"Perfumes\"]},"
            + "{\"id\": \"1.3\", \"layout\": \"table\", \"rows\": []}]}");
        assertEquals("table", compare.getLayout());
        assertEquals(List.of("Metallic conductors | Electrolytic conductors", "Electrons | Ions"), compare.getTableRows());
        assertEquals(List.of("Paints", "Varnishes", "Perfumes"), uses.getGalleryItems());
        assertEquals("bullets", other.getLayout());
    }

    @Test
    void onlyARealRecapGetsTheSummaryLayout() {
        SceneSegment remark = shot(1, "Therefore rho remains constant at the new length.", List.of());
        SceneSegment recap = shot(2, "To summarize, the new resistance is 1.21 times the old one.", List.of());
        LayoutPlanner.plan(lesson(remark, recap), (system, user) -> "{\"shots\": [{\"id\": \"1.1\", \"layout\": \"summary\"},"
            + "{\"id\": \"1.2\", \"layout\": \"summary\"}]}");
        assertEquals("bullets", remark.getLayout());
        assertEquals("summary", recap.getLayout());
    }
}
