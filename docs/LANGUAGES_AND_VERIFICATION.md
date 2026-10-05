# Languages, translation and source verification

## Supported languages
English, Hindi, Marathi, Tamil, Telugu, Malayalam, Kannada, Bengali, Gujarati, Punjabi, Odia, Urdu
(`LanguageSupport` is the single table: script, sentence terminators, narration voices, right-to-left flag).
Adding a language is one `add(...)` line there plus a prompt sentence in `whisper_transcribe.py` (`PROMPTS`).

**Input language = output language.** The recognised language of a video (or the script of a DOCX/text input) decides the
language of the storyboard, the contract's `language` package (BCP-47, script, direction, voice) and therefore the narration
voice, fonts and subtitles in the video generator. Technical terms may stay in English when the source uses them.

## Same storyboard in other languages
Translate, do not re-plan: shots, pictures, formulas, circuits and timings stay identical; only spoken/on-screen text changes.

* During a run: `storyboard.target.languages=ta,te` in `application.properties` (or `-Dstoryboard.target.languages=ta,te`).
* Afterwards, for any existing storyboard:
  `mvnw spring-boot:run -Dspring-boot.run.main-class=com.video.transcribe.StoryboardTranslatorRunner -Dspring-boot.run.arguments="<lesson_storyboard.json> <outdir> ta,te"`

Outputs per language: `<lesson>_<code>_storyboard.json|docx`, `<lesson>_<code>_contract.json`, `_audit.json`, `_grounding.json`.

Every translated string is validated: written in the target script, every digit-number preserved, chemical formulas/symbols
(`H2O`, `Ag+`) unchanged, plausible length. A string that still fails after one retry is kept but the shot's `review_notes`
say `TRANSLATION CHECK: ...` - a translator must look at exactly those shots. Label placements stay keyed to the translated
labels; circuit/graph rows keep their syntax (only captions are translated).

### Translation engines (`translation.engine`)
| Engine | What | Notes |
|--------|------|-------|
| `madlad` (preferred) | offline MADLAD-400 3B (Apache-2.0) in `translate_text.py`, one long-lived worker, bfloat16, about 6 GB VRAM | install once: `python -c "from huggingface_hub import snapshot_download as d; d('google/madlad400-3b-mt', local_dir=r'D:/AI/models/madlad400-3b-mt', allow_patterns=['*.json','*.model','model.safetensors'])"` (11.8 GB, no login) |
| `ollama` | local Qwen (`qwen3:14b`) | works with no extra install, but its Tamil/Telugu science vocabulary is weak (it mistranslated a title and a definition in the first test) |
| `auto` (default) | `madlad` when installed, else `ollama` | |

Other open options that need a free Hugging Face login and accepting terms: AI4Bharat IndicTrans2 (MIT). It is the strongest
Indic-specific model; add it as another `translate_text.py` backend if you create a token.

Machine translation of technical Indic text is never final: a fluent reviewer must read every translated lesson. Corrections go
into the glossary (below) and are applied to every later translation automatically; shots the validators doubt are marked
`TRANSLATION CHECK` in their review notes.

## Glossary (teacher-maintained)
See `glossary/README.md`. The recogniser writes `<lesson>_glossary_candidates.json` (its least-certain words); a person copies the
wrong ones into a glossary file. Corrections apply before paraphrasing, after translation, and bias recognition (hotwords).
No corrections are shipped by default.

## Source verification (`ClaimGrounding`)
Not a fact-checker - it cannot know whether a source is right. For every shot it reports whether the narration's key terms and
every number appear in the sources (transcript, paraphrase, approved reference materials). Output: `<lesson>_grounding.json`
and a `FACT CHECK` line in the review notes of shots that need a subject expert. Translated storyboards are compared against the
original by numbers only. Facts still need teacher sign-off; this makes sure they look at the right shots.

## Speech recognition for Tamil and Telugu (measured)
Real human read speech (Google FLEURS test sets, 30 utterances per language), word error rate / character error rate:

| Language | General `large-v3` (30 s windows) | General, best window | Fine-tuned specialist, best window |
|----------|------|------|------|
| Tamil | 48.6% / 14.9% | 48.6% / 14.9% | **19.3% / 7.8%** (22 s windows) |
| Telugu | 75.0% / 39.4% | 70.8% / 20.9% | **23.9% / 8.5%** (8 s windows) |

Two findings drive the configuration:
1. **Whisper's decoder holds at most 448 tokens per window**, and Indic scripts need many tokens per second of speech (measured on lesson narration:
   Telugu about 22 tokens/s, Tamil about 11, English about 3). A 30-second window of Telugu needs about 665 tokens, so most of it is dropped
   silently. `whisper_transcribe.py` therefore decodes Indic languages in shorter windows (`VALIDATED_WINDOWS`: Telugu 8 s, Tamil 22 s; 12 s for the
   other Indic languages until they are measured the same way).
2. **Fine-tuned models** (IIT Madras `vasista22/whisper-<language>-large-v2`, Apache-2.0, no login) are much better than the general model. Convert once:
   `ct2-transformers-converter --model <downloaded folder> --output_dir <folder>-ct2 --copy_files tokenizer.json preprocessor_config.json --quantization float16`
   (the tokenizer comes from `openai/whisper-large-v2`), then set `tool.whisper.specialists=ta=<ct2 folder>;te=<ct2 folder>`. The script detects the
   language with the general model and then switches to the specialist. The specialists do not write sentence punctuation; `TranscriptPunctuator` restores it.

Caveats: the numbers come from short read sentences, not from lectures with background noise; on synthetic text-to-speech lessons the
general model sometimes scores better, which is why real speech was used to decide. Always have a person check technical terms
(the glossary workflow covers that).

## ASR script
`tool.whisper.script` defaults to the project's `./whisper_transcribe.py`. At start-up the pipeline fails if it is missing and
logs its path and SHA-256; both are stored in the job manifest (`asr`). The script uses VAD, a per-language punctuation prompt,
glossary hotwords, repetition-loop detection with a second pass, and writes the glossary candidates file.

## Speed
* `ollama.cache.dir` (default `./llm_cache`): model answers cached by the complete request. Re-running a lesson after a code change
  reuses every unchanged answer; delete the folder for fresh answers.
* Extra languages are translated, not re-planned (minutes instead of the ~30 minutes of storyboard planning).
* Most of a run is scene planning and visual direction with the fast model; `OLLAMA_NUM_PARALLEL` and a larger GPU would help
  further but are machine settings, not code.
