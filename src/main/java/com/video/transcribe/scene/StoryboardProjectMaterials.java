package com.video.transcribe.scene;

import java.nio.file.Path;
import java.util.List;

/** Ordered project evidence supplied to storyboard discovery and SME review. */
public record StoryboardProjectMaterials(String promptContext, List<Path> approvedAssets) {
    public StoryboardProjectMaterials {
        promptContext = promptContext == null ? "" : promptContext;
        approvedAssets = approvedAssets == null ? List.of() : List.copyOf(approvedAssets);
    }

    public static StoryboardProjectMaterials empty() {
        return new StoryboardProjectMaterials("", List.of());
    }

    public boolean isEmpty() {
        return promptContext.isBlank() && approvedAssets.isEmpty();
    }
}
