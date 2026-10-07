package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FormulaReviewerTest {

    private static StoryboardDocument lesson(List<String>... formulas) {
        Scene scene = new Scene();
        scene.setSceneNumber(6);
        List<SceneSegment> segments = new ArrayList<>();
        int n = 1;
        for (List<String> lines : formulas) {
            SceneSegment s = new SceneSegment();
            s.setSegmentNumber(n++);
            s.setSentence("The limiting molar conductivity of acetic acid follows from strong electrolytes.");
            s.setFormulaLines(new ArrayList<>(lines));
            segments.add(s);
        }
        scene.setSegments(segments);
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Applications of Kohlrausch's Law");
        doc.setScenes(new ArrayList<>(List.of(scene)));
        return doc;
    }

    @Test
    void spelledOutGreekLettersBecomeSymbols() {
        assertEquals("\\lambda_m^0(CH_3COOH) = \\lambda_m^0(H^+)", FormulaReviewer.repairSyntax("lambda_m^0(CH_3COOH) = lambda_m^0(H^+)"));
        assertEquals("\\Delta G = \\Delta H - T\\Delta S", FormulaReviewer.repairSyntax("$Delta G = Delta H - T\\Delta S$"));
        assertEquals("\\lambda", FormulaReviewer.repairSyntax("\\lambda"));            // already a symbol: unchanged
        assertEquals("deltas = 2", FormulaReviewer.repairSyntax("deltas = 2"));          // a longer word is not a Greek letter
    }

    @Test
    void aWrongLawIsRemovedAndATypoIsFixed() {
        StoryboardDocument doc = lesson(
            List.of("\\Lambda_m^\\circ(weak) = \\Lambda_m^\\circ(strong) - \\Lambda_m^\\circ(strong)"),
            List.of("\\Lambda_m^\\circ(Electrolyte) = \\Lambda_m^\\circ(Cation) + \\Lambda_m^3(Anion)"),
            List.of("\\Lambda_m^\\circ = \\lambda_+^\\circ + \\lambda_-^\\circ"));
        List<FormulaReviewer.Finding> findings = FormulaReviewer.review(doc, (system, user) -> {
            if (system.contains("second, independent")) {
                return "{\"items\": [{\"id\": \"6.1#0\", \"answer\": \"wrong\"}, {\"id\": \"6.2#0\", \"answer\": \"wrong\"}]}";
            }
            assertTrue(system.contains("did not write"));
            assertTrue(user.contains("6.1#0") && user.contains("6.3#0"));
            return "{\"items\": [{\"id\": \"6.1#0\", \"verdict\": \"remove\", \"formula\": \"\", \"reason\": \"strong minus strong is meaningless\"},"
                + "{\"id\": \"6.2#0\", \"verdict\": \"fix\", \"formula\": \"\\\\Lambda_m^\\\\circ(Electrolyte) = \\\\Lambda_m^\\\\circ(Cation) + \\\\Lambda_m^\\\\circ(Anion)\", \"reason\": \"3 should be a degree sign\"},"
                + "{\"id\": \"6.3#0\", \"verdict\": \"ok\", \"formula\": \"\", \"reason\": \"\"}]}";
        });
        List<SceneSegment> shots = doc.getScenes().get(0).getSegments();
        assertTrue(shots.get(0).getFormulaLines().isEmpty());
        assertEquals("\\Lambda_m^\\circ(Electrolyte) = \\Lambda_m^\\circ(Cation) + \\Lambda_m^\\circ(Anion)", shots.get(1).getFormulaLines().get(0));
        assertEquals("\\Lambda_m^\\circ = \\lambda_+^\\circ + \\lambda_-^\\circ", shots.get(2).getFormulaLines().get(0));
        assertEquals(List.of("removed", "fixed"), findings.stream().map(FormulaReviewer.Finding::verdict).toList());
    }

    @Test
    void aCorrectLineIsKeptWhenTheSecondReviewerDisagrees() {
        StoryboardDocument doc = lesson(List.of("R \\propto \\frac{l}{A}"), List.of("v = u + 2at"));
        List<FormulaReviewer.Finding> findings = FormulaReviewer.review(doc, (system, user) -> {
            if (system.contains("second, independent")) {
                assertTrue(user.contains("claimed problem"));
                return "{\"items\": [{\"id\": \"6.1#0\", \"answer\": \"correct\"}, {\"id\": \"6.2#0\", \"answer\": \"wrong\"}]}";
            }
            return "{\"items\": [{\"id\": \"6.1#0\", \"verdict\": \"fix\", \"formula\": \"R = \\\\rho \\\\frac{l}{A}\", \"reason\": \"prefer the equation\"},"
                + "{\"id\": \"6.2#0\", \"verdict\": \"fix\", \"formula\": \"v = u + at\", \"reason\": \"wrong factor 2\"}]}";
        });
        List<SceneSegment> shots = doc.getScenes().get(0).getSegments();
        assertEquals(List.of("R \\propto \\frac{l}{A}"), shots.get(0).getFormulaLines());      // correct proportionality kept
        assertEquals(List.of("v = u + at"), shots.get(1).getFormulaLines());                  // a real error is still fixed
        assertEquals(List.of("kept", "fixed"), findings.stream().map(FormulaReviewer.Finding::verdict).toList());
    }

    @Test
    void aWrongCorrectionOfAWrongLineRemovesTheLine() {
        StoryboardDocument doc = lesson(
            List.of("\\Lambda(weak) = \\Lambda(strong) - \\Lambda(strong)_{corrected}"),
            List.of("\\Lambda(CH_3COOH) = \\Lambda(CH_3COONa) + \\Lambda(HCl) - \\Lambda(NaCl)"));
        FormulaReviewer.review(doc, (system, user) -> {
            if (system.contains("second, independent")) {
                assertTrue(user.contains("ALL EQUATIONS") && user.contains("CH_3COONa") && user.contains("proposed correction"));
                return "{\"items\": [{\"id\": \"6.1#0\", \"answer\": \"wrong\", \"correction_ok\": false}]}";
            }
            return "{\"items\": [{\"id\": \"6.1#0\", \"verdict\": \"fix\", \"formula\": \"\\\\Lambda(weak) = \\\\Lambda(strong_1) - \\\\Lambda(strong_2)\", \"reason\": \"undefined term\"}]}";
        });
        assertTrue(doc.getScenes().get(0).getSegments().get(0).getFormulaLines().isEmpty());     // neither version is shown
        assertEquals(1, doc.getScenes().get(0).getSegments().get(1).getFormulaLines().size());
    }

    @Test
    void anUnavailableReviewKeepsTheLinesAndAsksForATeacher() {
        StoryboardDocument doc = lesson(List.of("v = u + a t"));
        List<FormulaReviewer.Finding> findings = FormulaReviewer.review(doc, (s, u) -> { throw new java.io.IOException("Ollama down"); });
        assertEquals(List.of("v = u + a t"), doc.getScenes().get(0).getSegments().get(0).getFormulaLines());
        assertEquals("not_reviewed", findings.get(0).verdict());
    }
}
