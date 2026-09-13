package com.video.transcribe.scene;

import java.util.List;

public class SceneSegment {
	private int segmentNumber;
	private String sentence;
	private String mediaType;
	private String motionType;
	private double estimatedNarrationSeconds;
	private double recommendedClipSeconds;
	private String timingNotes;
	private String visualAnimation;
	private String localAnimation;
	private List<String> labels;
	private List<String> imageRecommendations;
	private String comfyPrompt;
	private String coverageNotes;
	private Shot shot;

	public int getSegmentNumber() {
		return segmentNumber;
	}

	public void setSegmentNumber(int segmentNumber) {
		this.segmentNumber = segmentNumber;
	}

	public String getSentence() {
		return sentence;
	}

	public void setSentence(String sentence) {
		this.sentence = sentence;
	}

	public String getMediaType() {
		return mediaType;
	}

	public void setMediaType(String mediaType) {
		this.mediaType = mediaType;
	}

	public String getMotionType() {
		return motionType;
	}

	public void setMotionType(String motionType) {
		this.motionType = motionType;
	}

	public double getEstimatedNarrationSeconds() {
		return estimatedNarrationSeconds;
	}

	public void setEstimatedNarrationSeconds(double estimatedNarrationSeconds) {
		this.estimatedNarrationSeconds = estimatedNarrationSeconds;
	}

	public double getRecommendedClipSeconds() {
		return recommendedClipSeconds;
	}

	public void setRecommendedClipSeconds(double recommendedClipSeconds) {
		this.recommendedClipSeconds = recommendedClipSeconds;
	}

	public String getTimingNotes() {
		return timingNotes;
	}

	public void setTimingNotes(String timingNotes) {
		this.timingNotes = timingNotes;
	}

	public String getVisualAnimation() {
		return visualAnimation;
	}

	public void setVisualAnimation(String visualAnimation) {
		this.visualAnimation = visualAnimation;
	}

	public String getLocalAnimation() {
		return localAnimation;
	}

	public void setLocalAnimation(String localAnimation) {
		this.localAnimation = localAnimation;
	}

	public List<String> getLabels() {
		return labels;
	}

	public void setLabels(List<String> labels) {
		this.labels = labels;
	}

	public List<String> getImageRecommendations() {
		return imageRecommendations;
	}

	public void setImageRecommendations(List<String> imageRecommendations) {
		this.imageRecommendations = imageRecommendations;
	}

	public String getComfyPrompt() {
		return comfyPrompt;
	}

	public void setComfyPrompt(String comfyPrompt) {
		this.comfyPrompt = comfyPrompt;
	}

	public String getCoverageNotes() {
		return coverageNotes;
	}

	public void setCoverageNotes(String coverageNotes) {
		this.coverageNotes = coverageNotes;
	}

	public Shot getShot() {
		return shot;
	}

	public void setShot(Shot shot) {
		this.shot = shot;
	}
}
