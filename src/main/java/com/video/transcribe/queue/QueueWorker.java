package com.video.transcribe.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.VideoRephrasePipeline;
import com.video.transcribe.VideoRephrasePipeline.PipelineResult;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.queue.VideoQueue.QueueItem;
import com.video.transcribe.queue.VideoQueue.VideoStatus;

/**
 * Worker that processes videos ONE BY ONE from the queue
 * Windows GPU compatible
 */
public class QueueWorker implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueWorker.class);
    
    private final VideoQueue queue;
    private final VideoRephrasePipeline pipeline;
    private final String language;
    private final String style;
    private volatile boolean running = true;
    private volatile boolean paused = false;
    
    /**
     * Constructor - takes AppConfig, creates pipeline internally
     */
    public QueueWorker(VideoQueue queue, AppConfig config) {
        this.queue = queue;
        this.pipeline = new VideoRephrasePipeline(config);
        this.language = config.getLanguage();
        this.style = config.getRephraseStyle();
    }
    
    @SuppressWarnings("unused")
	@Override
    public void run() {
        logger.info("Queue worker started - processing ONE BY ONE");
        
        while (running) {
            try {
                if (paused) {
                    Thread.sleep(1000);
                    continue;
                }
                
                QueueItem item = queue.take();
                VideoStatus status = queue.getVideoStatus(item.getId());
                
                logger.info("╔════════════════════════════════════════════════════╗");
                logger.info("║ STARTING: {}", padRight(item.getSource().getFileName(), 40) + " ║");
                logger.info("║ Queue remaining: {}", padRight(String.valueOf(queue.getStatus().queueSize), 33) + " ║");
                logger.info("╚════════════════════════════════════════════════════╝");
                
                long startTime = System.currentTimeMillis();
                
                try {
                    java.nio.file.Path videoPath = item.getSource().getVideoPath();
                    
                    PipelineResult result = pipeline.processVideo(
                        videoPath.toString(), 
                        language, 
                        style
                    );
                    
                    if (result.success) {
                        queue.markCompleted(item.getId());
                        long duration = System.currentTimeMillis() - startTime;
                        
                        logger.info("╔════════════════════════════════════════════════════╗");
                        logger.info("║ ✓ COMPLETED: {}", padRight(item.getSource().getFileName(), 36) + " ║");
                        logger.info("║ Time: {}", padRight(formatDuration(duration), 41) + " ║");
                        logger.info("║ Output: {}", padRight(result.finalVideo.toString(), 38) + " ║");
                        logger.info("╚════════════════════════════════════════════════════╝");
                    } else {
                        queue.markFailed(item.getId(), result.error);
                        logger.error("✗ FAILED: {} - {}", 
                            item.getSource().getFileName(), result.error);
                    }
                    
                } catch (Exception e) {
                    queue.markFailed(item.getId(), e.getMessage());
                    logger.error("✗ ERROR processing {}: {}", 
                        item.getSource().getFileName(), e.getMessage(), e);
                    
                } finally {
                    if (item.getSource().isUrl()) {
                        item.getSource().cleanup();
                    }
                    logger.info(queue.getStatus().toString());
                }
                
            } catch (InterruptedException e) {
                logger.info("Worker interrupted");
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        logger.info("Queue worker stopped");
        pipeline.shutdown();
    }
    
    public void stop() { running = false; }
    public void pause() { paused = true; logger.info("Worker PAUSED"); }
    public void resume() { paused = false; logger.info("Worker RESUMED"); }
    public boolean isPaused() { return paused; }
    
    private String padRight(String s, int n) {
        if (s.length() > n) return s.substring(0, n);
        return String.format("%-" + n + "s", s);
    }
    
    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds % 60);
        return String.format("%ds", seconds);
    }
}