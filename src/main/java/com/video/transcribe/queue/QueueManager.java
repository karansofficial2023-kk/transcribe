package com.video.transcribe.queue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.config.AppConfig;
import com.video.transcribe.source.LocalFileSource;
import com.video.transcribe.source.UrlVideoSource;

/**
 * Manages the queue: watches folders, accepts URLs, starts worker
 */
public class QueueManager {

	private static final Logger logger = LoggerFactory.getLogger(QueueManager.class);

	private final VideoQueue queue;
	private final String tempDir;
	private final ExecutorService executor;
	private Thread workerThread;
	private WatchService watchService;
	private volatile boolean watching = false;

	public QueueManager(String tempDir) {
		this.queue = new VideoQueue();
		this.tempDir = tempDir;
		this.executor = Executors.newCachedThreadPool();
		new File(tempDir).mkdirs();
	}

	/**
	 * Add videos from a folder (scans existing + watches for new)
	 */
	public void addFolder(String folderPath, boolean watchForNew) throws Exception {
		File folder = new File(folderPath);
		if (!folder.exists() || !folder.isDirectory()) {
			throw new IOException("Invalid folder: " + folderPath);
		}

		// Add existing videos
		File[] files = folder.listFiles((dir, name) -> isLessonInput(name));      // videos, transcripts/notes and topic requests

		if (files != null) {
			for (File f : files) {
				queue.enqueue(new LocalFileSource(f.getAbsolutePath()));
			}
		}

		logger.info("Added {} existing lesson inputs (videos, texts, topics) from: {}", files != null ? files.length : 0, folderPath);

		// Watch for new files
		if (watchForNew) {
			startWatching(folderPath);
		}
	}

	/**
	 * Add a single URL to queue
	 */
	public void addUrl(String url) {
		queue.enqueue(new UrlVideoSource(url, tempDir));
		logger.info("Added URL to queue: {}", url);
	}

	/**
	 * Add multiple URLs
	 */
	public void addUrls(List<String> urls) {
		for (String url : urls) {
			addUrl(url);
		}
	}

	/**
	 * Start folder watcher (new files auto-added to queue)
	 */
	/** Videos already queued from the folder watcher (one copy raises several file-system events). */
	private final java.util.Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Anything the queue can turn into a lesson: a video, a transcript/notes file (.txt .docx .pdf) or a topic request (.topic.json). */
	static boolean isLessonInput(String name) {
		return com.video.transcribe.source.InputKind.of(name) != null;
	}

	static boolean isVideoFile(String name) {
		return name != null && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|mov|avi|mkv|wmv|webm|flv)$");
	}

	/** A file that is still being copied keeps growing: wait until its size has been stable for a few seconds (at most 30 minutes). */
	private boolean waitUntilCopied(Path file) throws InterruptedException {
		long last = -1;
		int stable = 0;
		for (int i = 0; i < 1800 && watching; i++) {
			long size;
			try {
				size = java.nio.file.Files.size(file);
			} catch (IOException e) {
				size = -1;
			}
			stable = size > 0 && size == last ? stable + 1 : 0;
			if (stable >= 3) {
				return true;
			}
			last = size;
			Thread.sleep(1000);
		}
		return false;
	}

	private void startWatching(String folderPath) throws Exception {
		watchService = FileSystems.getDefault().newWatchService();
		Path path = Paths.get(folderPath);

		path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

		watching = true;

		executor.submit(() -> {
			logger.info("Watching folder for new videos: {}", folderPath);

			while (watching) {
				try {
					WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
					if (key == null)
						continue;

					try {
						for (WatchEvent<?> event : key.pollEvents()) {
							if (event.kind() == StandardWatchEventKinds.OVERFLOW || !(event.context() instanceof Path)) {
								continue;               // the OS dropped events: nothing to read, and the watcher must keep running
							}
							Path fileName = (Path) event.context();
							String fullPath = folderPath + "/" + fileName;
							if (!isLessonInput(fileName.toString()) || !announced.add(fullPath)) {
								continue;               // not a video, or this copy already produced a queue entry (CREATE + MODIFY...)
							}
							if (!waitUntilCopied(Paths.get(fullPath))) {
								announced.remove(fullPath);
								continue;
							}
							queue.enqueue(new LocalFileSource(fullPath));
							logger.info("New video detected and queued: {}", fileName);
						}
					} finally {
						key.reset();                    // always, or this folder is never watched again
					}
					continue;
				} catch (Exception e) {
					logger.error("Watch error: {}", e.getMessage());
				}
			}
		});
	}

	/**
	 * Start processing queue (ONE BY ONE)
	 */
	 public void startProcessing(AppConfig config) {
	        QueueWorker worker = new QueueWorker(queue, config);
	        workerThread = new Thread(worker, "VideoQueueWorker");
	        workerThread.start();
	        logger.info("Queue processing started (one-by-one mode)");
	    }

	/**
	 * Get current queue status
	 */
	public VideoQueue.QueueStatus getStatus() {
		return queue.getStatus();
	}

	/**
	 * Pause processing
	 */
	public void pause() {
		if (workerThread != null) {
			// Note: actual pause is handled in worker
			logger.info("Pause requested");
		}
	}

	/**
	 * Stop everything
	 */
	public void stop() {
		watching = false;
		if (watchService != null) {
			try {
				watchService.close();
			} catch (IOException e) {
				// ignore
			}
		}
		if (workerThread != null) {
			workerThread.interrupt();
		}
		executor.shutdown();
		logger.info("Queue manager stopped");
	}

	/**
	 * Print status report
	 */
	public void printStatus() {
		VideoQueue.QueueStatus status = getStatus();
		System.out.println("\n" + "=".repeat(60));
		System.out.println("  QUEUE STATUS REPORT");
		System.out.println("=".repeat(60));
		System.out.println("  Total Submitted:  " + status.totalSubmitted);
		System.out.println("  Completed:          " + status.totalProcessed);
		System.out.println("  Failed:             " + status.totalFailed);
		System.out.println("  Pending:            " + status.getPendingCount());
		System.out.println("  In Queue:           " + status.queueSize);
		System.out.println("-".repeat(60));

		for (VideoQueue.VideoStatus vs : status.allStatuses) {
			String symbol = switch (vs.getStatus()) {
			case PENDING -> "⏳";
			case PROCESSING -> "▶️";
			case COMPLETED -> "✅";
			case FAILED -> "❌";
			};
			System.out.printf("  %s %-40s %s%n", symbol, vs.getFileName(), vs.getStatus());
		}
		System.out.println("=".repeat(60));
	}
}