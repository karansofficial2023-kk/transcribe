package com.video.transcribe.validator;

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

    private ValidationResult result(double overall, double scientific, double coverage, double hallucination) {
        ValidationResult result = new ValidationResult();
        result.setOverallScore(overall);
        result.setScientificAccuracyScore(scientific);
        result.setTopicCoverageScore(coverage);
        result.setHallucinationScore(hallucination);
        return result;
    }
}
