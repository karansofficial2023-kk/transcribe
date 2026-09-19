package com.video.transcribe.scene;

import java.util.List;

public class SceneSegment {
	private int segmentNumber;
	private String sentence;
	private String template;
	private String visualType;
	private String heading;
	private String visualSubject;
	private String assetPath;
	private String mediaType;
	private String motionType;
	private double estimatedNarrationSeconds;
	private double recommendedClipSeconds;
	private String timingNotes;
	private String visualAnimation;
	private String localAnimation;
	private List<String> labels;
	private List<String> labelPlacements;
	private String labelStyle;
	private List<String> arrows;
	private List<String> highlights;
	private List<String> formulaLines;
	private List<String> explainSteps;
	private List<String> steps;
	private List<String> columns;
	private List<String> imageRecommendations;
	private String motion;
	private String subtitle;
	private String subtitleStyle;
	private String tool;
	private String assetQualityNotes;
	private String comfyPrompt;
	private String coverageNotes;
	private Shot shot;
	private Shot ltxShot;

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

	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	public String getVisualType() {
		return visualType;
	}

	public void setVisualType(String visualType) {
		this.visualType = visualType;
	}

	public String getHeading() {
		return heading;
	}

	public void setHeading(String heading) {
		this.heading = heading;
	}

	public String getVisualSubject() {
		return visualSubject;
	}

	public void setVisualSubject(String visualSubject) {
		this.visualSubject = visualSubject;
	}

	public String getAssetPath() {
		return assetPath;
	}

	public void setAssetPath(String assetPath) {
		this.assetPath = assetPath;
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

	public List<String> getLabelPlacements() {
		return labelPlacements;
	}

	public void setLabelPlacements(List<String> labelPlacements) {
		this.labelPlacements = labelPlacements;
	}

	public String getLabelStyle() {
		return labelStyle;
	}

	public void setLabelStyle(String labelStyle) {
		this.labelStyle = labelStyle;
	}

	public List<String> getArrows() {
		return arrows;
	}

	public void setArrows(List<String> arrows) {
		this.arrows = arrows;
	}

	public List<String> getHighlights() {
		return highlights;
	}

	public void setHighlights(List<String> highlights) {
		this.highlights = highlights;
	}

	public List<String> getFormulaLines() {
		return formulaLines;
	}

	public void setFormulaLines(List<String> formulaLines) {
		this.formulaLines = formulaLines;
	}

	public List<String> getExplainSteps() {
		return explainSteps;
	}

	public void setExplainSteps(List<String> explainSteps) {
		this.explainSteps = explainSteps;
	}

	public List<String> getSteps() {
		return steps;
	}

	public void setSteps(List<String> steps) {
		this.steps = steps;
	}

	public List<String> getColumns() {
		return columns;
	}

	public void setColumns(List<String> columns) {
		this.columns = columns;
	}

	public List<String> getImageRecommendations() {
		return imageRecommendations;
	}

	public void setImageRecommendations(List<String> imageRecommendations) {
		this.imageRecommendations = imageRecommendations;
	}

	public String getMotion() {
		return motion;
	}

	public void setMotion(String motion) {
		this.motion = motion;
	}

	public String getSubtitle() {
		return subtitle;
	}

	public void setSubtitle(String subtitle) {
		this.subtitle = subtitle;
	}

	public String getSubtitleStyle() {
		return subtitleStyle;
	}

	public void setSubtitleStyle(String subtitleStyle) {
		this.subtitleStyle = subtitleStyle;
	}

	public String getTool() {
		return tool;
	}

	public void setTool(String tool) {
		this.tool = tool;
	}

	public String getAssetQualityNotes() {
		return assetQualityNotes;
	}

	public void setAssetQualityNotes(String assetQualityNotes) {
		this.assetQualityNotes = assetQualityNotes;
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

	public Shot getLtxShot() {
		return ltxShot;
	}

	public void setLtxShot(Shot ltxShot) {
		this.ltxShot = ltxShot;
	}
}
