package com.video.transcribe.scene;

import java.util.List;

public class Scene {
	private int sceneNumber;
	private String sceneTitle;
	private String narration;
	private List<SceneSegment> segments;

	public int getSceneNumber() {
		return sceneNumber;
	}

	public void setSceneNumber(int sceneNumber) {
		this.sceneNumber = sceneNumber;
	}

	public String getSceneTitle() {
		return sceneTitle;
	}

	public void setSceneTitle(String sceneTitle) {
		this.sceneTitle = sceneTitle;
	}

	public String getNarration() {
		return narration;
	}

	public void setNarration(String narration) {
		this.narration = narration;
	}

	public List<SceneSegment> getSegments() {
		return segments;
	}

	public void setSegments(List<SceneSegment> segments) {
		this.segments = segments;
	}
}