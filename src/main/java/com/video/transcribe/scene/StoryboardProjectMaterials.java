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

    /**
     * True when at least one text document (notes, script, PDF, slides ...) was supplied. Only text evidence can justify adding
     * content to a lesson; images and videos alone are visual candidates and prove no fact.
     */
    public boolean hasTextEvidence() {
        return promptContext.contains("[PRIORITY ");
    }

    public boolean isEmpty() {
        return promptContext.isBlank() && approvedAssets.isEmpty();
    }
}
