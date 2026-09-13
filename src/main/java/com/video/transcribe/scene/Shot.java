package com.video.transcribe.scene;

public class Shot {
    private String template;
    private String heading;
    private String prompt;
    private String negativePrompt;
    private double durationSeconds;
    private double clipDurationSeconds;
    private int clipCount;
    private String joinInstructions;

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }

    public void setNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public double getClipDurationSeconds() {
        return clipDurationSeconds;
    }

    public void setClipDurationSeconds(double clipDurationSeconds) {
        this.clipDurationSeconds = clipDurationSeconds;
    }

    public int getClipCount() {
        return clipCount;
    }

    public void setClipCount(int clipCount) {
        this.clipCount = clipCount;
    }

    public String getJoinInstructions() {
        return joinInstructions;
    }

    public void setJoinInstructions(String joinInstructions) {
        this.joinInstructions = joinInstructions;
    }
}
