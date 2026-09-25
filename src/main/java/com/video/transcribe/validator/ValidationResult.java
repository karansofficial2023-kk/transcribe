package com.video.transcribe.validator;

import java.util.List;

public class ValidationResult {
	private int originalLength;
	private int paraphrasedLength;
	private double semanticSimilarityScore;
	private double factualConsistencyScore;
	private double scientificAccuracyScore;
	private double keyConceptPreservationScore;
	private double topicCoverageScore;
	private double hallucinationScore;
	private double overallScore;
	private boolean passed;
	private String timestamp;
	private List<String> issues;

	// Getters and Setters
	public int getOriginalLength() {
		return originalLength;
	}

	public void setOriginalLength(int originalLength) {
		this.originalLength = originalLength;
	}

	public int getParaphrasedLength() {
		return paraphrasedLength;
	}

	public void setParaphrasedLength(int paraphrasedLength) {
		this.paraphrasedLength = paraphrasedLength;
	}

	public double getSemanticSimilarityScore() {
		return semanticSimilarityScore;
	}

	public void setSemanticSimilarityScore(double semanticSimilarityScore) {
		this.semanticSimilarityScore = semanticSimilarityScore;
	}

	public double getFactualConsistencyScore() {
		return factualConsistencyScore;
	}

	public void setFactualConsistencyScore(double factualConsistencyScore) {
		this.factualConsistencyScore = factualConsistencyScore;
	}

	public double getScientificAccuracyScore() {
		return scientificAccuracyScore;
	}

	public void setScientificAccuracyScore(double scientificAccuracyScore) {
		this.scientificAccuracyScore = scientificAccuracyScore;
	}

	public double getKeyConceptPreservationScore() {
		return keyConceptPreservationScore;
	}

	public void setKeyConceptPreservationScore(double keyConceptPreservationScore) {
		this.keyConceptPreservationScore = keyConceptPreservationScore;
	}

	public double getTopicCoverageScore() {
		return topicCoverageScore;
	}

	public void setTopicCoverageScore(double topicCoverageScore) {
		this.topicCoverageScore = topicCoverageScore;
	}

	public double getHallucinationScore() {
		return hallucinationScore;
	}

	public void setHallucinationScore(double hallucinationScore) {
		this.hallucinationScore = hallucinationScore;
	}

	public double getOverallScore() {
		return overallScore;
	}

	public void setOverallScore(double overallScore) {
		this.overallScore = overallScore;
	}

	public boolean isPassed() {
		return passed;
	}

	public void setPassed(boolean passed) {
		this.passed = passed;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}

	public List<String> getIssues() {
		return issues;
	}

	public void setIssues(List<String> issues) {
		this.issues = issues;
	}
}
