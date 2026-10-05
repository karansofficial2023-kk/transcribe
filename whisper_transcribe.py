#!/usr/bin/env python3
"""
Open-source speech recognition (faster-whisper) called by the Java pipeline.

What it does beyond plain transcription:
  * voice-activity filtering, so silence and music do not turn into invented text;
  * a per-language initial prompt that carries correct punctuation and script, so unpunctuated ASR output is rare;
  * glossary hotwords (subject terms, supplied by the pipeline) re-applied to every decoding window;
  * repetition-loop detection with an automatic second pass that does not condition on previous text;
  * a glossary-candidates file listing low-confidence words, for a teacher to correct once and reuse.
"""

import argparse
import json
import re
import sys
import unicodedata
import warnings
from collections import Counter
from pathlib import Path

warnings.filterwarnings("ignore")

# A short, natural sentence in the lesson language. It teaches the decoder the script and that sentences end with the
# language's own terminator; it is never part of the output text.
PROMPTS = {
    "en": "This is a science lesson. In it, we look at the key ideas, step by step.",
    "hi": "यह एक विज्ञान का पाठ है। इसमें, हम मुख्य बातें देखेंगे।",
    "mr": "हा एक विज्ञानाचा धडा आहे. यात, आपण मुख्य मुद्दे पाहू.",
    "ta": "இது ஒரு அறிவியல் பாடம். இதில், நாம் முக்கியமான கருத்துகளைப் பார்ப்போம்.",
    "te": "ఇది ఒక సైన్స్ పాఠం. ఇందులో, మనం ముఖ్యమైన అంశాలను చూద్దాం.",
    "ml": "ഇത് ഒരു ശാസ്ത്ര പാഠമാണ്. ഇതിൽ, നമ്മൾ പ്രധാന ആശയങ്ങൾ കാണും.",
    "kn": "ಇದು ಒಂದು ವಿಜ್ಞಾನ ಪಾಠ. ಇದರಲ್ಲಿ, ನಾವು ಪ್ರಮುಖ ಅಂಶಗಳನ್ನು ನೋಡುತ್ತೇವೆ.",
    "bn": "এটি একটি বিজ্ঞানের পাঠ। এতে, আমরা মূল বিষয়গুলো দেখব।",
    "gu": "આ એક વિજ્ઞાનનો પાઠ છે. આમાં, આપણે મુખ્ય મુદ્દાઓ જોઈશું.",
    "pa": "ਇਹ ਇੱਕ ਵਿਗਿਆਨ ਦਾ ਪਾਠ ਹੈ। ਇਸ ਵਿੱਚ, ਅਸੀਂ ਮੁੱਖ ਗੱਲਾਂ ਵੇਖਾਂਗੇ।",
    "ur": "یہ سائنس کا ایک سبق ہے۔ اس میں، ہم اہم نکات دیکھیں گے۔",
}


# Whisper's decoder holds at most 448 tokens per window, and Indic scripts need many tokens per second of speech (measured:
# Telugu about 22, Tamil about 11, English about 3). A default 30 s window of Telugu would need about 665 tokens, so most of it is
# silently dropped. Shorter windows fix that. Values for ta and te were chosen on real read speech (FLEURS, 30 utterances each);
# the others are a conservative middle until they are measured the same way.
VALIDATED_WINDOWS = {"te": 8, "ta": 22}
DEFAULT_INDIC_WINDOW = 12
INDIC = {"hi", "mr", "ml", "kn", "bn", "gu", "pa", "or", "ur", "as", "ne", "si"}


def window_seconds(language):
    """Window length for a language, or None for the model's default (30 s)."""
    code = (language or "").split("-")[0].lower()
    if code in VALIDATED_WINDOWS:
        return VALIDATED_WINDOWS[code]
    return DEFAULT_INDIC_WINDOW if code in INDIC else None


def parse_specialists(value):
    """'ta=D:/models/ta-ct2;te=D:/models/te-ct2' -> {'ta': path, ...}, keeping only models that exist on disk."""
    found = {}
    for part in (value or "").split(";"):
        code, _, path = part.partition("=")
        code, path = code.strip().lower(), path.strip()
        if code and path and Path(path).is_dir():
            found[code] = path
    return found


def initial_prompt(language):
    return PROMPTS.get((language or "").split("-")[0].lower())


# Speech recognisers invent fluent sentences in trailing silence or noise ("thanks for watching", news-like lines). Such segments are
# typically zero-length or far too long for their time span. Measured real speech: 11 (Tamil) to 20 (Telugu) letters per second.
MAX_LETTERS_PER_SECOND = 40.0
MIN_SPAN_SECONDS = 0.3


def impossible_segment(segment):
    """True for text that cannot have been spoken in the time the recogniser assigned to it."""
    letters = sum(1 for ch in segment["text"] if ch.isalpha())
    if letters < 8:
        return False
    span = segment["end"] - segment["start"]
    return span < MIN_SPAN_SECONDS or letters / span > MAX_LETTERS_PER_SECOND


def drop_impossible_segments(segments):
    kept = [s for s in segments if not impossible_segment(s)]
    for index, segment in enumerate(kept):
        segment["id"] = index
    return kept, len(segments) - len(kept)


def _plain(text):
    """Lower-cased text with punctuation, symbols and spaces collapsed. Combining marks (Indic vowel signs, viramas) are kept: they are
    part of the word, and regex \W would treat them as punctuation and cut words apart."""
    kept = " ".join("".join(" " if unicodedata.category(ch)[0] in "PSZC" else ch for ch in (text or "").lower()).split())
    return kept


def _trim(word):
    """Strip leading and trailing punctuation or symbols from a word, never its combining marks."""
    start, end = 0, len(word)
    while start < end and unicodedata.category(word[start])[0] in "PSZ":
        start += 1
    while end > start and unicodedata.category(word[end - 1])[0] in "PSZ":
        end -= 1
    return word[start:end]


def repetition_loops(texts, run=3):
    """Number of places where the same segment text repeats `run` or more times in a row (a classic ASR failure)."""
    loops, streak, previous = 0, 1, None
    for text in texts:
        key = _plain(text)
        if key and key == previous:
            streak += 1
            if streak == run:
                loops += 1
        else:
            streak = 1
        previous = key
    return loops


def uncertain_words(segments, threshold=0.85, limit=80):
    """Words the model was unsure about, most doubtful first: candidates for a teacher-maintained glossary."""
    found = {}
    for segment in segments:
        for word in segment.get("words", []):
            token = _trim(word.get("word", ""))
            probability = word.get("probability")
            if len(token) < 3 or probability is None or probability >= threshold or token.isdigit():
                continue
            entry = found.setdefault(token.lower(), {"heard": token, "count": 0, "min_probability": 1.0, "example": segment["text"]})
            entry["count"] += 1
            entry["min_probability"] = round(min(entry["min_probability"], probability), 3)
    ranked = sorted(found.values(), key=lambda e: (e["min_probability"], -e["count"]))
    return ranked[:limit]


def write_candidates(output_json, language, segments):
    if not output_json:
        return None
    path = Path(output_json)
    base = re.sub(r"_transcript$", "", path.stem)
    target = path.with_name(f"{base}_glossary_candidates.json")
    candidates = uncertain_words(segments)
    target.write_text(json.dumps({
        "language": language,
        "how_to_use": "Check each 'heard' word. For a wrong one, add {\"heard\": \"correct\"} to glossary/<lang>.json, to <materials>/glossary.json "
                      "or to <materials>/<lesson>_glossary.json; the correction is applied before paraphrasing and also biases recognition.",
        "candidates": candidates}, ensure_ascii=False, indent=2), encoding="utf-8")
    return target


def run_faster_whisper(model, audio_path, language, prompt, hotwords, condition, window=None):
    segments, info = model.transcribe(
        audio_path,
        language=language,
        initial_prompt=prompt,
        hotwords=hotwords or None,
        word_timestamps=True,
        beam_size=5,
        vad_filter=True,                       # drop silence/music so ASR does not hallucinate text
        vad_parameters=dict(min_silence_duration_ms=2500, speech_pad_ms=500),
        condition_on_previous_text=condition,
        hallucination_silence_threshold=2.0,        # skip silent stretches where the model starts inventing text
        **({"chunk_length": window} if window else {}),
    )
    result = {"text": "", "language": info.language, "duration": info.duration, "segments": []}
    for segment in segments:
        data = {"id": len(result["segments"]), "start": segment.start, "end": segment.end, "text": segment.text.strip(), "words": []}
        for word in segment.words or []:
            data["words"].append({"word": word.word.strip(), "start": word.start, "end": word.end, "probability": word.probability})
        result["segments"].append(data)
        result["text"] += segment.text + " "
    result["segments"], dropped = drop_impossible_segments(result["segments"])
    result["text"] = " ".join(segment["text"] for segment in result["segments"]).strip()
    result["dropped_segments"] = dropped
    return result


def transcribe(audio_path, model_size="base", language=None, output_json=None, device="cuda", hotwords=None, specialists=None):
    for stream in (sys.stdout, sys.stderr):         # a Tamil or Hindi file name must never crash the print after the transcript is written
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    try:
        try:
            # Load the installed PyTorch CUDA libraries for CTranslate2 on Windows.
            if sys.platform == "win32" and device == "cuda":
                import os
                import torch

                os.add_dll_directory(str(Path(torch.__file__).parent / "lib"))

            from faster_whisper import WhisperModel

            print(f"Using faster-whisper with model: {model_size}", file=sys.stderr)
            model = WhisperModel(model_size, device=device, compute_type="int8_float16" if device == "cuda" else "int8")

            if language is None:
                try:
                    from faster_whisper.audio import decode_audio
                    language, probability, _ = model.detect_language(decode_audio(audio_path, sampling_rate=16000)[:16000 * 30])
                    print(f"Detected language {language} ({probability:.2f})", file=sys.stderr)
                except Exception as error:      # detection is a convenience; the decoder still detects on its own
                    print(f"Language pre-detection skipped: {error}", file=sys.stderr)
                    language = None

            specialist = (specialists or {}).get((language or "").split("-")[0].lower())
            if specialist:
                # a model fine-tuned for this language is far more accurate on it than the general model that detected it
                print(f"Switching to the {language} specialist model: {specialist}", file=sys.stderr)
                del model
                import gc
                gc.collect()
                try:
                    import torch

                    torch.cuda.empty_cache()
                except Exception:
                    pass
                model = WhisperModel(specialist, device=device, compute_type="int8_float16" if device == "cuda" else "int8")
                model_size = specialist

            prompt = initial_prompt(language)
            window = window_seconds(language)
            # short windows are decoded independently: conditioning on the previous window would only propagate its mistakes
            result = run_faster_whisper(model, audio_path, language, prompt, hotwords, condition=window is None, window=window)
            loops = repetition_loops(s["text"] for s in result["segments"])
            retried = False
            if loops:
                print(f"{loops} repetition loop(s) found; retrying without conditioning on previous text", file=sys.stderr)
                second = run_faster_whisper(model, audio_path, language, prompt, hotwords, condition=False, window=window)
                retried = True
                if repetition_loops(s["text"] for s in second["segments"]) < loops:
                    result, loops = second, repetition_loops(s["text"] for s in second["segments"])
            result["quality"] = {"repetition_loops": loops, "retried_without_context": retried, "prompted": bool(prompt),
                                 "hotwords": bool(hotwords), "model": str(model_size), "window_seconds": window or 30,
                                 "dropped_hallucinated_segments": result.get("dropped_segments", 0)}
        except ImportError:
            import whisper     # fallback to the reference implementation

            print(f"Using openai-whisper with model: {model_size}", file=sys.stderr)
            model = whisper.load_model(model_size).to(device)
            raw = model.transcribe(audio_path, language=language, task="transcribe", word_timestamps=True,
                                   initial_prompt=initial_prompt(language))
            result = {"text": raw["text"], "language": raw.get("language", "unknown"), "duration": raw.get("duration", 0),
                      "segments": [{"id": seg.get("id", 0), "start": seg.get("start", 0), "end": seg.get("end", 0),
                                    "text": seg.get("text", "").strip(), "words": seg.get("words", [])} for seg in raw.get("segments", [])]}

        candidates_file = write_candidates(output_json, result.get("language"), result["segments"])
        if candidates_file:
            print(f"Glossary candidates: {candidates_file}", file=sys.stderr)
        if output_json:
            with open(output_json, "w", encoding="utf-8") as handle:
                json.dump(result, handle, ensure_ascii=False, indent=2)
            print(f"Saved transcript to: {output_json}")
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        return result
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Transcribe audio using local Whisper")
    parser.add_argument("audio", help="Path to audio file")
    parser.add_argument("--model", default="base", help="Model size")
    parser.add_argument("--language", default=None, help="Language code")
    parser.add_argument("--output", default=None, help="Output JSON file")
    parser.add_argument("--device", default="cuda", help="Device: cuda or cpu")
    parser.add_argument("--specialists", default=None, help="Per-language models: 'ta=PATH;te=PATH' (CTranslate2 folders)")
    parser.add_argument("--hotwords-file", default=None, help="UTF-8 text file, one subject term per line (biases recognition)")
    args = parser.parse_args()
    terms = []
    if args.hotwords_file:
        with open(args.hotwords_file, encoding="utf-8") as handle:
            terms = [line.strip() for line in handle if line.strip()]
    transcribe(args.audio, args.model, args.language, args.output, args.device, " ".join(terms[:50]) or None,
               parse_specialists(args.specialists))
