package com.video.transcribe.scene;

import java.util.List;

public class StoryboardDocument {
	private String title;
	private String subject;
	private String topic;
	private String smeRole;
	private String sourceText;
	private List<Scene> scenes;
	private String generatedAt;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getTopic() {
		return topic;
	}

	public void setTopic(String topic) {
		this.topic = topic;
	}

	public String getSmeRole() {
		return smeRole;
	}

	public void setSmeRole(String smeRole) {
		this.smeRole = smeRole;
	}

	public String getSourceText() {
		return sourceText;
	}

	public void setSourceText(String sourceText) {
		this.sourceText = sourceText;
	}

	public List<Scene> getScenes() {
		return scenes;
	}

	public void setScenes(List<Scene> scenes) {
		this.scenes = scenes;
	}

	public String getGeneratedAt() {
		return generatedAt;
	}

	public void setGeneratedAt(String generatedAt) {
		this.generatedAt = generatedAt;
	}
}
