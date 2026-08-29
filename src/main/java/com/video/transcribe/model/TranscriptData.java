package com.video.transcribe.model;

import java.util.List;

/**
 * Transcript data model matching Whisper JSON output
 */
public class TranscriptData {
    private String text;
    private String language;
    private double duration;
    private List<Segment> segments;
    
    public String getFullText() { return text; }
    public void setText(String text) { this.text = text; }
    
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    
    public double getDuration() { return duration; }
    public void setDuration(double duration) { this.duration = duration; }
    
    public List<Segment> getSegments() { return segments; }
    public void setSegments(List<Segment> segments) { this.segments = segments; }
    
    public static class Segment {
        private int id;
        private double start;
        private double end;
        private String text;
        private List<Word> words;
        
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        
        public double getStart() { return start; }
        public void setStart(double start) { this.start = start; }
        
        public double getEnd() { return end; }
        public void setEnd(double end) { this.end = end; }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public List<Word> getWords() { return words; }
        public void setWords(List<Word> words) { this.words = words; }
    }
    
    public static class Word {
        private String word;
        private double start;
        private double end;
        private double probability;
        
        public String getWord() { return word; }
        public void setWord(String word) { this.word = word; }
        
        public double getStart() { return start; }
        public void setStart(double start) { this.start = start; }
        
        public double getEnd() { return end; }
        public void setEnd(double end) { this.end = end; }
        
        public double getProbability() { return probability; }
        public void setProbability(double probability) { this.probability = probability; }
    }
}