package com.video.transcribe.scene;

import java.util.List;

public class SceneSegment {
	private int segmentNumber;
	private String sentence;
	private String visualAnimation;
	private List<String> imageRecommendations;

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

	public String getVisualAnimation() {
		return visualAnimation;
	}

	public void setVisualAnimation(String visualAnimation) {
		this.visualAnimation = visualAnimation;
	}

	public List<String> getImageRecommendations() {
		return imageRecommendations;
	}

	public void setImageRecommendations(List<String> imageRecommendations) {
		this.imageRecommendations = imageRecommendations;
	}
}