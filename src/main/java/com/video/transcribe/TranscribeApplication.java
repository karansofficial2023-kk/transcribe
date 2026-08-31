package com.video.transcribe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.video.transcribe.config.AppConfig;
import com.video.transcribe.queue.QueueManager;

@SpringBootApplication
public class TranscribeApplication {

	public static void main(String[] args) throws Exception {
		
		// Load configuration
		AppConfig config = new AppConfig();
		config.printConfig();

		// Create queue manager
		QueueManager queueManager = new QueueManager(config.getTempDir());

		// Add videos based on config
		String inputType = config.getInputType();

		switch (inputType.toLowerCase()) {
		case "folder" -> {
			queueManager.addFolder(config.getInputFolder(), config.isWatchEnabled());
		}
		case "url" -> {
			String url = config.getInputUrl();
			if (url.isEmpty()) {
				System.err.println("ERROR: video.input.url is empty in application.properties");
				System.exit(1);
			}
			queueManager.addUrl(url);
		}
		case "urlfile" -> {
			String urlFile = config.getInputUrlFile();
			if (urlFile.isEmpty()) {
				System.err.println("ERROR: video.input.urlfile is empty");
				System.exit(1);
			}
			java.nio.file.Path path = java.nio.file.Paths.get(urlFile);
			List<String> urls = java.nio.file.Files.readAllLines(path);
			queueManager.addUrls(urls);
			System.out.println("Loaded " + urls.size() + " URLs from " + urlFile);
		}
		default -> {
			System.err.println("ERROR: Unknown input type: " + inputType);
			System.exit(1);
		}
		}

		// Start processing - pass config ONLY (QueueWorker creates pipeline internally)
		queueManager.startProcessing(config);

		// Status monitor
		Thread monitor = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(30000);
					queueManager.printStatus();
				} catch (InterruptedException e) {
					break;
				}
			}
		});
		monitor.setDaemon(true);
		monitor.start();

		// Interactive console
		System.out.println("\n" + "=".repeat(60));
		System.out.println("  AUDIO-ONLY PROCESSING QUEUE STARTED");
		System.out.println("  Flow: Video → Transcript → Paraphrase → TTS Audio");
		System.out.println("  Mode: " + (config.isSequential() ? "ONE BY ONE" : "PARALLEL"));
		System.out.println("  GPU: " + config.getWhisperDevice().toUpperCase());
		System.out.println("=".repeat(60));
		System.out.println("Commands: status | add <url> | pause | resume | stop");
		System.out.println("-".repeat(60));

		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;

		while ((line = reader.readLine()) != null) {
			String[] parts = line.trim().split("\\s+", 2);
			String cmd = parts[0].toLowerCase();

			switch (cmd) {
			case "status" -> queueManager.printStatus();
			case "add" -> {
				if (parts.length > 1) {
					queueManager.addUrl(parts[1]);
					System.out.println("Added: " + parts[1]);
				} else {
					System.out.println("Usage: add <url>");
				}
			}
			case "stop", "exit", "quit" -> {
				System.out.println("Stopping...");
				queueManager.stop();
				System.out.println("Goodbye!");
				return;
			}
			default -> System.out.println("Unknown: " + cmd);
			}
		}
	}

}