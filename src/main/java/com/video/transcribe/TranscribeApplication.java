package com.video.transcribe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
		String singleInput = System.getenv("TRANSCRIBE_INPUT_FILE");
		String inputManifest = System.getenv("TRANSCRIBE_INPUT_MANIFEST");

		if (inputManifest != null && !inputManifest.isBlank()) {
			Path manifestPath = Path.of(inputManifest.trim());
			if (!Files.isRegularFile(manifestPath)) {
				throw new java.io.IOException("Input manifest does not exist: " + manifestPath);
			}
			List<String> inputFiles = Files.readAllLines(manifestPath, StandardCharsets.UTF_8).stream()
					.map(String::trim).filter(line -> !line.isEmpty()).distinct().toList();
			if (inputFiles.isEmpty()) {
				throw new IllegalArgumentException("Input manifest contains no video paths: " + manifestPath);
			}
			for (String inputFile : inputFiles) queueManager.addFile(inputFile);
			System.out.println("Loaded " + inputFiles.size() + " video paths from " + manifestPath);
		} else if (singleInput != null && !singleInput.isBlank()) {
			queueManager.addFile(singleInput.trim());
		} else switch (inputType.toLowerCase()) {
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

		if (config.isExitWhenComplete()) {
			while (true) {
				Thread.sleep(2000);
				var status = queueManager.getStatus();
				if (status.totalSubmitted > 0
						&& status.totalSubmitted == status.totalProcessed + status.totalFailed
						&& status.queueSize == 0) {
						queueManager.stop();
						if (status.totalFailed > 0) System.exit(2);
						return;
					}
			}
		}

		// Interactive console
		System.out.println("\n" + "=".repeat(60));
		System.out.println("  AUDIO-ONLY PROCESSING QUEUE STARTED");
		System.out.println("  Flow: Video → Transcript → Paraphrase → storyboard with scene →TTS Audio");
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
