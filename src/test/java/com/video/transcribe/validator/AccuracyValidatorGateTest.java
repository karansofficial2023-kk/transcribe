package com.video.transcribe.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccuracyValidatorGateTest {

    @Test
    void rejectsHighOverallScoreWhenScientificAccuracyIsWeak() {
        ValidationResult result = result(92, 50, 95, 95);
        assertFalse(AccuracyValidator.passesQualityGates(result));
    }

    @Test
    void acceptsNarrationOnlyWhenEveryProductionGatePasses() {
        ValidationResult result = result(90, 92, 90, 96);
        assertTrue(AccuracyValidator.passesQualityGates(result));
    }

    @Test
    void identifiesTheExactFailedGateWhenOverallScoreIsHigh() {
        ValidationResult result = result(92.05, 75, 100, 100);

        assertEquals(
            java.util.List.of("scientific accuracy 75.00/100 (required 85.00)"),
            AccuracyValidator.failedQualityGates(result, 80.0)
        );
    }

    @Test
    void respectsAStricterConfiguredOverallThreshold() {
        ValidationResult result = result(92.05, 90, 100, 100);

        assertEquals(
            java.util.List.of("overall score 92.05/100 (required 95.00)"),
            AccuracyValidator.failedQualityGates(result, 95.0)
        );
    }

    @Test
    void retainsScientificClaimsForRepairFeedback() {
        String response = """
            ```json
            {
              "score": 75,
              "incorrect_claims": ["Claim A reverses cause and effect."],
              "uncertain_claims": ["Example B needs a species-specific source."]
            }
            ```
            """;

        assertEquals(
            java.util.List.of(
                "Scientific accuracy: Claim A reverses cause and effect.",
                "Scientific uncertainty: Example B needs a species-specific source."
            ),
            AccuracyValidator.extractScientificIssues(response)
        );
    }

    @Test
    void parsesIssueEvaluatorObjectsAsWellAsArrays() {
        String response = """
            ```json
            {"issues": ["Remove an unsupported example."], "severity": "high"}
            ```
            """;

        assertEquals(
            java.util.List.of("Remove an unsupported example."),
            AccuracyValidator.extractIssueList(response)
        );
    }

    @Test
    void skipsRedundantSupplementalScanWhenValidationPassed() {
        ValidationResult result = result(92, 92, 92, 92);
        result.setPassed(true);

        assertFalse(AccuracyValidator.needsSupplementalIssueScan(result, java.util.List.of()));
    }

    @Test
    void requestsSupplementalScanOnlyForUnexplainedFailure() {
        ValidationResult result = result(70, 92, 92, 92);
        result.setPassed(false);

        assertTrue(AccuracyValidator.needsSupplementalIssueScan(result, java.util.List.of()));
        assertFalse(AccuracyValidator.needsSupplementalIssueScan(
            result, java.util.List.of("Semantic fidelity is below threshold.")));
    }

    private ValidationResult result(double overall, double scientific, double coverage, double hallucination) {
        ValidationResult result = new ValidationResult();
        result.setOverallScore(overall);
        result.setScientificAccuracyScore(scientific);
        result.setTopicCoverageScore(coverage);
        result.setHallucinationScore(hallucination);
        return result;
    }
}
