# Production storyboard pipeline (Transcribe)

Implements `Transcribe_Storyboard_Production_Specification.pdf`. Output per lesson (in `video.output.dir`):

| File | Purpose |
|---|---|
| `<base>_storyboard.docx` | human review: one vertical field table per shot |
| `<base>_contract.json` | machine contract v2.0 - same field names/values as the DOCX; the Python generator reads this |
| `<base>_audit.json` | structured issues `{shot_id, field, severity, reason, repair}` (labels, formulas) |
| `<base>_manifest.json` | source path, bytes, duration, SHA-256, language; duplicate media reuses the earlier transcript |
| `<base>_storyboard.draft.json` | pre-gate draft, kept when the quality gate rejects a storyboard |

## Pipeline
intake (SHA-256) -> FFmpeg audio -> faster-whisper -> paraphrase + accuracy gate -> curriculum enrichment/fact audit ->
scene + shot plan -> **visual director** -> quality gate -> DOCX + contract JSON + audit.

### Visual director (`scene/VisualDirector.java`, `storyboard.visual.director=true`)
One schema-constrained Qwen request per scene proposes, per shot, a specific text-free image prompt and - only when the
narration supports them - visible-structure labels (`label | morphology | COORDINATES_PENDING_APPROVED_IMAGE`) or an
ordered step list. Proposals are validated deterministically against the narration (labels must come from it, prompts must
overlap it, steps must not be fragments of it); a rejected proposal leaves the shot unchanged. No topic vocabulary is used.

Re-plan an existing storyboard without re-running transcription:
`mvnw spring-boot:run -Dspring-boot.run.main-class=com.video.transcribe.VisualDirectorRunner -Dspring-boot.run.arguments="<storyboard.json> <outdir>"`

Runtime overrides are JVM properties, e.g. `-Dvideo.input.folder=... -Dvideo.output.dir=... -Dtemp.dir=...`
(`AppConfig` reads `-Dkey=value` for any key present in `application.properties`).

## Speed and VRAM policy (RTX 3060 12 GB)
- **Qwen3 "thinking" is off** (`ollama.think=false`): hidden reasoning tokens made every call ~4x slower. Sampling now really applies
  (`options.temperature=0.2`; the old top-level `temperature`/`num_predict` fields were silently ignored by Ollama).
- **Two models:** `tool.ollama.model` (qwen3:14b) for paraphrase, enrichment and fact audit; `tool.ollama.model.fast` (qwen3:8b, larger context)
  for scene planning and the visual director. Models are unloaded (`keep_alive: 0`) before Whisper runs.
- **Whisper large-v3** (`int8_float16`, VAD, language-specific punctuation prompt). Unpunctuated ASR output is repaired word-for-word by
  `TranscriptPunctuator` before anything sentence-based runs.
- **GPU lease** (`GpuLease`): the job holds `%TEMP%\open_edu_gpu.lock` for its whole run; the Python generator uses the same lock, so Whisper/Ollama and
  FLUX/vision never fight over the card.

## Visual director modes (subject-independent validation)
`realistic_image`, `realistic_labeled_image` (narration-named visible parts only; abstract labels rejected), `process_steps`
(key ideas/steps reusing narration words), `split_screen` (named subjects), `formula` (plain-math lines, numbers must be stated),
`graph` (bar rows with narration numbers, or a function plot `y = ...` with `x range`), `circuit` (series/parallel rows, battery value stated).
Subject guidance (`SubjectProfiles`) only tells the director how a discipline is best shown; no lesson content is encoded.

## Language
Whisper's detected language selects the contract `language` package (voice, script, direction). Free-text LLM calls on non-Latin input
carry a rule to keep language and script (never translate); tokenisation treats combining marks as part of words so Indic text is measured correctly.
