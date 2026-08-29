package com.video.transcribe.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.source.VideoSource;

/**
 * Thread-safe queue for video processing
 * Supports one-by-one processing with status tracking
 */
public class VideoQueue {
    
    private static final Logger logger = LoggerFactory.getLogger(VideoQueue.class);
    
    private final BlockingQueue<QueueItem> queue;
    private final Map<String, VideoStatus> statusMap;
    private final AtomicInteger totalSubmitted;
    private final AtomicInteger totalProcessed;
    private final AtomicInteger totalFailed;
    
    public VideoQueue() {
        this.queue = new LinkedBlockingQueue<>();
        this.statusMap = new ConcurrentHashMap<>();
        this.totalSubmitted = new AtomicInteger(0);
        this.totalProcessed = new AtomicInteger(0);
        this.totalFailed = new AtomicInteger(0);
    }
    
    /**
     * Add video to queue
     */
    public void enqueue(VideoSource source) {
        String id = source.getId();
        QueueItem item = new QueueItem(id, source);
        
        queue.offer(item);
        statusMap.put(id, new VideoStatus(id, source.getFileName(), Status.PENDING));
        totalSubmitted.incrementAndGet();
        
        logger.info("Queued: {} (Queue size: {})", source.getFileName(), queue.size());
    }
    
    /**
     * Add multiple videos
     */
    public void enqueueAll(List<VideoSource> sources) {
        for (VideoSource source : sources) {
            enqueue(source);
        }
    }
    
    /**
     * Get next video (blocks until available)
     */
    public QueueItem take() throws InterruptedException {
        QueueItem item = queue.take();
        statusMap.get(item.getId()).setStatus(Status.PROCESSING);
        return item;
    }
    
    /**
     * Mark as completed
     */
    public void markCompleted(String id) {
        statusMap.get(id).setStatus(Status.COMPLETED);
        totalProcessed.incrementAndGet();
    }
    
    /**
     * Mark as failed
     */
    public void markFailed(String id, String error) {
        VideoStatus status = statusMap.get(id);
        status.setStatus(Status.FAILED);
        status.setError(error);
        totalFailed.incrementAndGet();
    }
    
    /**
     * Get current status
     */
    public QueueStatus getStatus() {
        return new QueueStatus(
            totalSubmitted.get(),
            totalProcessed.get(),
            totalFailed.get(),
            queue.size(),
            new ArrayList<>(statusMap.values())
        );
    }
    
    /**
     * Get status of specific video
     */
    public VideoStatus getVideoStatus(String id) {
        return statusMap.get(id);
    }
    
    // Inner classes
    
    public static class QueueItem {
        private final String id;
        private final VideoSource source;
        
        public QueueItem(String id, VideoSource source) {
            this.id = id;
            this.source = source;
        }
        
        public String getId() { return id; }
        public VideoSource getSource() { return source; }
    }
    
    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
    
    public static class VideoStatus {
        private final String id;
        private final String fileName;
        private volatile Status status;
        private volatile String error;
        private volatile long startTime;
        private volatile long endTime;
        
        public VideoStatus(String id, String fileName, Status status) {
            this.id = id;
            this.fileName = fileName;
            this.status = status;
            this.startTime = System.currentTimeMillis();
        }
        
        public String getId() { return id; }
        public String getFileName() { return fileName; }
        public Status getStatus() { return status; }
        public void setStatus(Status status) { 
            this.status = status; 
            if (status == Status.COMPLETED || status == Status.FAILED) {
                this.endTime = System.currentTimeMillis();
            }
        }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public long getDurationMs() { 
            return endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime; 
        }
    }
    
    public static class QueueStatus {
        public final int totalSubmitted;
        public final int totalProcessed;
        public final int totalFailed;
        public final int queueSize;
        public final List<VideoStatus> allStatuses;
        
        public QueueStatus(int totalSubmitted, int totalProcessed, int totalFailed, 
                          int queueSize, List<VideoStatus> allStatuses) {
            this.totalSubmitted = totalSubmitted;
            this.totalProcessed = totalProcessed;
            this.totalFailed = totalFailed;
            this.queueSize = queueSize;
            this.allStatuses = allStatuses;
        }
        
        public int getPendingCount() {
            return totalSubmitted - totalProcessed - totalFailed;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Queue Status: %d submitted | %d processed | %d failed | %d pending | %d in queue",
                totalSubmitted, totalProcessed, totalFailed, getPendingCount(), queueSize
            );
        }
    }
}