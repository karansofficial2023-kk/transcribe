package com.video.transcribe.source;

import java.nio.file.Path;

/**
 * Interface for video sources - supports both local files and URLs
 */
public interface VideoSource {

	/**
	 * Get unique identifier for this source
	 */
	String getId();

	/**
	 * Get the video file path (downloads if URL)
	 */
	Path getVideoPath() throws Exception;

	/**
	 * Get original filename
	 */
	String getFileName();

	/**
	 * Clean up downloaded files (for URLs)
	 */
	void cleanup();

	/**
	 * Whether this is a URL source
	 */
	boolean isUrl();
}