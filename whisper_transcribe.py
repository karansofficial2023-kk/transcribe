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
import os
import re
import shutil
import subprocess
import tempfile
import sys
import unicodedata
import warnings
import importlib.util
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


_PROTECTED_FORMULA = re.compile(r"(?<![A-Za-z0-9])(?:[A-Z][a-z]?\d*)+(?![A-Za-z0-9])")
_PROTECTED_NUMBER = re.compile(r"(?<![A-Za-z0-9])\d+(?:\.\d+)?(?![A-Za-z0-9])")


def protected_tokens(text):
    """Return formula/number tokens whose disagreement must never be auto-corrected."""
    formulas = [token.upper() for token in _PROTECTED_FORMULA.findall(text or "")
                if any(char.isdigit() for char in token) or len(token) >= 2]
    numbers = _PROTECTED_NUMBER.findall(text or "")
    return {"formulas": formulas, "numbers": numbers}


def protected_evidence(primary_text, alternate_text):
    """Two-ASR agreement gate. OCR confirmation is added later by visual_source_qa."""
    primary, alternate = protected_tokens(primary_text), protected_tokens(alternate_text)
    return {"method": "two_asr_or_ocr", "asr_agreement": primary == alternate,
            "primary": primary, "alternate": alternate,
            "requires_ocr_or_teacher_review": primary != alternate,
            "ocr_confirmed": False}


def _demucs_vocal_stem(audio_path, folder):
    """Return an isolated vocal stem when Demucs is available; never modify the source audio."""
    if os.environ.get("WHISPER_STEM_SEPARATION", "1") == "0":
        return str(audio_path), {"requested": False, "used": False, "engine": "none"}
    python = os.environ.get("DEMUCS_PYTHON", sys.executable)
    if Path(python).resolve() == Path(sys.executable).resolve() and importlib.util.find_spec("demucs") is None:
        return str(audio_path), {"requested": True, "used": False, "engine": "demucs/htdemucs",
                                 "reason": "demucs_not_installed"}
    out = Path(folder) / "stems"
    command = [python, "-m", "demucs", "--two-stems=vocals", "-n",
               os.environ.get("DEMUCS_MODEL", "htdemucs"), "--out", str(out), str(audio_path)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=1800, check=False)
        candidates = list(out.rglob("vocals.wav"))
        if result.returncode == 0 and candidates:
            return str(candidates[0]), {"requested": True, "used": True, "engine": "demucs/htdemucs"}
        reason = (result.stderr or result.stdout or "demucs produced no vocal stem")[-500:]
    except (OSError, subprocess.TimeoutExpired) as error:
        reason = str(error)
    return str(audio_path), {"requested": True, "used": False, "engine": "demucs/htdemucs", "reason": reason}


def prepare_asr_audio(audio_path):
    """Isolate speech then normalize it for ASR, while retaining the untouched original as evidence."""
    folder = tempfile.mkdtemp(prefix="asr_preprocessed_")
    stem_audio, stem = _demucs_vocal_stem(audio_path, folder)
    if os.environ.get("WHISPER_PREPROCESS", "1") == "0":
        return stem_audio, folder, {**stem, "original_audio": str(Path(audio_path).resolve()),
                                    "asr_audio": str(Path(stem_audio).resolve()), "normalized": False}
    ffmpeg = os.environ.get("FFMPEG_PATH", "ffmpeg")
    target = Path(folder) / "audio.wav"
    command = [ffmpeg, "-v", "error", "-y", "-i", str(stem_audio), "-map", "0:a:0", "-vn",
               "-af", "highpass=f=70,lowpass=f=7600,loudnorm=I=-18:TP=-2:LRA=11",
               "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(target)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=300, check=False)
        if result.returncode == 0 and target.is_file():
            return str(target), folder, {**stem, "original_audio": str(Path(audio_path).resolve()),
                                         "asr_audio": str(target.resolve()), "normalized": True}
    except (OSError, subprocess.TimeoutExpired):
        pass
    return stem_audio, folder, {**stem, "original_audio": str(Path(audio_path).resolve()),
                                "asr_audio": str(Path(stem_audio).resolve()), "normalized": False,
                                "normalization_error": True}


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


def uncertain_numbers(segments, threshold=0.6, limit=40):
    """Digits and number words the model doubted (a garbled "1 in 10" becomes "I in you"). Listed separately for review."""
    spoken = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "hundred", "thousand"}
    found = []
    for segment in segments:
        for word in segment.get("words", []):
            token = _trim(word.get("word", ""))
            probability = word.get("probability")
            if probability is None or probability >= threshold or not token:
                continue
            if token.isdigit() or token.lower() in spoken:
                found.append({"heard": token, "probability": round(probability, 3), "start": word.get("start"),
                              "example": segment["text"]})
    return sorted(found, key=lambda e: e["probability"])[:limit]


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
        "candidates": candidates, "uncertain_numbers": uncertain_numbers(segments)}, ensure_ascii=False, indent=2), encoding="utf-8")
    return target


# Characters that can never belong in English lesson speech (CJK, Cyrillic, Arabic, Thai, Hangul ...). Whisper occasionally
# emits them when it is unsure ("the球", "曹s"); they poison translation and dubbing, so they are removed and counted.
_FOREIGN_FOR_LATIN = re.compile(
    "[Ѐ-ӿ԰-֏֐-ࣿ฀-໿ᄀ-ᇿ⺀-⿟　-ヿ"
    "㄀-ㄯ㆐-鿿ꥠ-꥿가-퟿豈-﫿︰-﹏＀-￯]")
LATIN_LANGUAGES = {"en"}
MIN_WORD_SECONDS = 0.04


def sanitize_script(text, language):
    """Remove stray foreign-script characters from Latin-script (English) ASR text. Returns (text, removed_count)."""
    if (language or "").split("-")[0].lower() not in LATIN_LANGUAGES or not text:
        return text, 0
    cleaned, removed = _FOREIGN_FOR_LATIN.subn("", text)
    return (re.sub(r"[ 	]{2,}", " ", cleaned).strip() if removed else text), removed


# Terms real STEM lessons were misheard on (Buckminsterfullerene -> "Buck", hexagonal -> "Host", arm AB, slip ring S1 ...).
# Used after the lesson's own glossary terms; set WHISPER_STEM_VOCAB=0 to switch off for a non-STEM batch.
STEM_VOCABULARY = ("AC motor, conducting loop ABCD, arm AB, arm CD, slip rings S1 and S2, brushes B1 and B2, Fleming's left-hand rule, "
                   "armature, commutator, allotropes of carbon, graphite, diamond, buckminsterfullerene, C60, hexagonal, pentagonal, "
                   "carbonyl, aldehyde CHO, ketone, carboxylic acid COOH, amine NH2, alkane, IUPAC")


def join_split_decimals(text):
    """ASR often writes a decimal with a gap: "0 .5 %" -> "0.5 %" (the gap made the number check report a missing 0)."""
    return re.sub(r"(?<=\d) \.(?=\d)", ".", text or "")


_WHOLE_WORDS = set("the and for but are was not you all can her his its our out one two who how why now new old say see use any may got let put too yet nor off own per via few way".split())


def repair_split_words(segments):
    """A word cut by a segment boundary: "... to form bra." + "brass. ..." -> "... to form brass." + "brass. ...". The first segment ends in a
    fragment that the next segment (starting lower-case, i.e. not a new sentence) begins with and completes. Returns (segments, count)."""
    repaired = 0
    for current, following in zip(segments, segments[1:]):
        words = (current.get("text") or "").split()
        nxt = (following.get("text") or "").split()
        if not words or not nxt:
            continue
        tail = re.sub(r"[^A-Za-z]", "", words[-1])
        head = re.sub(r"[^A-Za-z]", "", nxt[0])
        if (len(tail) >= 3 and tail.lower() not in _WHOLE_WORDS and words[-1].endswith((".", ",")) and nxt[0][:1].islower()
                and head.lower().startswith(tail.lower()) and 1 <= len(head) - len(tail) <= 3):
            words[-1] = head + words[-1][len(re.sub(r"[.,]+$", "", words[-1])):]
            current["text"] = " ".join(words)
            repaired += 1
    return segments, repaired


def lesson_prompt(language, hotwords):
    """Base prompt plus the lesson vocabulary, so rare terms (Buckminsterfullerene) are biased in from the start."""
    base = initial_prompt(language) or ""
    if (language or "").split("-")[0].lower() in LATIN_LANGUAGES:
        vocabulary = (hotwords or "")[:400]
        if os.environ.get("WHISPER_STEM_VOCAB", "1") != "0":
            vocabulary = (vocabulary + " " + STEM_VOCABULARY).strip()
        if vocabulary:
            return (base + " Vocabulary: " + vocabulary).strip()
    return base or None


CLOSING_HINT = os.environ.get(
    "WHISPER_CLOSING_HINT", "Watch the upcoming videos to find out. Keep imbibing, we believe in you.")
TAIL_WINDOW_SECONDS = 18.0


def _decode_tail_words(model, samples, offset, language, hint):
    """Words of one tail decode, with absolute timestamps, after dropping segments Whisper itself doubts."""
    segments, _info = model.transcribe(
        samples[int(offset * 16000):], language=language, initial_prompt=hint, word_timestamps=True, beam_size=5,
        vad_filter=True, vad_parameters=dict(threshold=0.25, min_silence_duration_ms=2500, speech_pad_ms=500),
        condition_on_previous_text=False, hallucination_silence_threshold=2.0)
    found = []
    for segment in segments:
        if segment.no_speech_prob > 0.6 and segment.avg_logprob < -1.0:
            continue
        for word in segment.words or []:
            token, _removed = sanitize_script(word.word.strip(), language)
            if token:
                start = word.start + offset
                found.append({"word": token, "start": start, "end": max(word.end + offset, start + MIN_WORD_SECONDS),
                              "probability": word.probability})
    return found


def _sentences_from_words(words):
    """Group words into sentence-sized segments (end punctuation or a pause of a second or more)."""
    segments, current = [], []
    for index, word in enumerate(words):
        current.append(word)
        pause = index + 1 < len(words) and words[index + 1]["start"] - word["end"] >= 1.0
        if re.search(r"[.!?]$", word["word"]) or pause or index + 1 == len(words):
            segments.append({"start": current[0]["start"], "end": current[-1]["end"],
                             "text": " ".join(item["word"] for item in current), "words": current})
            current = []
    return segments


def rescue_tail(model, audio_path, result, language, prompt):
    """Recover the closing words of the lesson ("Keep imbibing, we believe in you").

    They are spoken over music after the last sentence and the long main decode often drops or truncates them. The last
    sentences are therefore decoded again on their own, with and without a hint of the series' closing line, and the fuller
    result replaces the main decode of that stretch (only when it agrees with it, so a hallucination cannot overwrite real
    text). Returns the number of words gained; stored as result["tail_rescued_words"].
    """
    result["tail_rescued_words"] = 0
    try:
        from difflib import SequenceMatcher
        from faster_whisper.audio import decode_audio
        samples = decode_audio(audio_path, sampling_rate=16000)
        total = len(samples) / 16000.0
        segments = result["segments"]
        if not segments:
            return 0
        # Replace whole segments: begin at the first segment that ends inside the final window.
        first = next((i for i, seg in enumerate(segments) if seg["end"] >= total - TAIL_WINDOW_SECONDS), len(segments) - 1)
        offset = max(0.0, segments[first]["start"] - 0.2)
        main_words = [w for seg in segments[first:] for w in seg.get("words", [])]
        hints = [None] + ([CLOSING_HINT] if CLOSING_HINT and (language or "").split("-")[0].lower() in LATIN_LANGUAGES else [])
        best = []
        for hint in hints:
            candidate = _decode_tail_words(model, samples, offset, language, hint)
            if (len(candidate), candidate[-1]["end"] if candidate else 0.0) > (len(best), best[-1]["end"] if best else 0.0):
                best = candidate
        main_text = " ".join(w["word"] for w in main_words).lower()
        best_text = " ".join(w["word"] for w in best).lower()
        agrees = (not main_words) or SequenceMatcher(None, main_text, best_text, autojunk=False).ratio() >= 0.5
        main_end = max((w["end"] for w in main_words), default=0.0)
        if not best or not agrees or best[-1]["end"] < main_end - 0.5 or len(best) <= len(main_words):
            return 0
        gained = len(best) - len(main_words)
        replacement = _sentences_from_words(best)
        result["segments"] = segments[:first] + replacement
        for number, seg in enumerate(result["segments"]):
            seg["id"] = number
        result["text"] = " ".join(seg["text"] for seg in result["segments"]).strip()
        result["tail_rescued_words"] = gained
        print(f"Tail rescue: closing stretch re-decoded, +{gained} word(s) (now ends {best[-1]['end']:.1f}s)", file=sys.stderr)
        return gained
    except Exception as error:                      # a rescue failure must never lose the main transcript
        print(f"Tail rescue skipped: {error}", file=sys.stderr)
        return 0


def run_faster_whisper(model, audio_path, language, prompt, hotwords, condition, window=None,
                       beam_size=5, min_silence_duration_ms=2500):
    segments, info = model.transcribe(
        audio_path,
        language=language,
        initial_prompt=prompt,
        hotwords=hotwords or None,
        word_timestamps=True,
        beam_size=beam_size,
        vad_filter=True,                       # drop silence/music so ASR does not hallucinate text
        vad_parameters=dict(min_silence_duration_ms=min_silence_duration_ms, speech_pad_ms=500),
        condition_on_previous_text=condition,
        hallucination_silence_threshold=2.0,        # skip silent stretches where the model starts inventing text
        **({"chunk_length": window} if window else {}),
    )
    result = {"text": "", "language": info.language, "duration": info.duration, "segments": []}
    foreign_removed = 0
    for segment in segments:
        text, removed = sanitize_script(segment.text.strip(), language or info.language)
        text = join_split_decimals(text)
        foreign_removed += removed
        data = {"id": len(result["segments"]), "start": segment.start, "end": segment.end, "text": text, "words": []}
        for word in segment.words or []:
            token, word_removed = sanitize_script(word.word.strip(), language or info.language)
            foreign_removed += word_removed
            if not token:
                continue
            # Whisper often gives digits/short tokens a zero-length span; keep them with a minimal duration so
            # downstream sentence rebuilding does not silently drop numbers.
            end = max(word.end, word.start + MIN_WORD_SECONDS)
            if data["words"] and re.fullmatch(r"\.\d+%?", token) and re.search(r"\d$", data["words"][-1]["word"]):
                data["words"][-1]["word"] += token            # "0" + ".5" is one number, 0.5
                data["words"][-1]["end"] = end
                continue
            data["words"].append({"word": token, "start": word.start, "end": end, "probability": word.probability})
        result["segments"].append(data)
        result["text"] += text + " "
    result["foreign_script_removed"] = foreign_removed
    result["segments"], dropped = drop_impossible_segments(result["segments"])
    result["segments"], result["split_words_repaired"] = repair_split_words(result["segments"])
    result["text"] = " ".join(segment["text"] for segment in result["segments"]).strip()
    result["dropped_segments"] = dropped
    return result


def _alternate_decode(model, audio_path, language, prompt, hotwords, window=None):
    """Independent decode with stricter beam search and shorter VAD silence merging."""
    return run_faster_whisper(model, audio_path, language, prompt, hotwords,
                              condition=False, window=window, beam_size=8,
                              min_silence_duration_ms=900)


def _whisperx_decode(audio_path, language, model_size, device):
    """Optional WhisperX pass. It is an evidence pass; it never silently overwrites formulas."""
    try:
        import whisperx
    except ImportError:
        return None
    try:
        compute = "float16" if device == "cuda" else "int8"
        model = whisperx.load_model(model_size, device, compute_type=compute, language=language)
        raw = model.transcribe(audio_path, batch_size=4, language=language)
        result = {"text": raw.get("text", ""), "language": raw.get("language", language),
                  "duration": 0, "segments": raw.get("segments", [])}
        result["quality"] = {"engine": "whisperx", "alignment": False}
        return result
    except Exception as error:
        print(f"WhisperX evidence pass skipped: {error}", file=sys.stderr)
        return None


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

            prompt = lesson_prompt(language, hotwords)
            window = window_seconds(language)
            # short windows are decoded independently: conditioning on the previous window would only propagate its mistakes
            # v2: conditioning on previous text made Whisper repeat phrases across its internal 30 s windows ("made up of a
            # number of atoms", "the carboxylic compound will be called as an acetyl"), collapse word timestamps and stop
            # punctuating a 60 s stretch. Measured on real lessons: similarity to an independent pass rose 0.961 -> 0.987 and
            # 0.967 -> 0.980 with it switched off, and phantom/collapsed segments went to 0. Set WHISPER_CONDITION=1 to restore.
            condition = os.environ.get("WHISPER_CONDITION", "0") == "1" and window is None
            prepared_audio, temp_audio_dir, audio_preparation = prepare_asr_audio(audio_path)
            result = run_faster_whisper(model, prepared_audio, language, prompt, hotwords, condition=condition, window=window)
            rescue_tail(model, audio_path, result, language, prompt)
            loops = repetition_loops(s["text"] for s in result["segments"])
            retried = False
            if loops:
                print(f"{loops} repetition loop(s) found; retrying without conditioning on previous text", file=sys.stderr)
                second = run_faster_whisper(model, audio_path, language, prompt, hotwords, condition=False, window=window)
                retried = True
                if repetition_loops(s["text"] for s in second["segments"]) < loops:
                    result, loops = second, repetition_loops(s["text"] for s in second["segments"])
            alternate = None
            if os.environ.get("WHISPER_ALTERNATE", "1") != "0":
                alternate = _alternate_decode(model, prepared_audio, language, prompt, hotwords, window)
                # Preserve the primary transcript unless the alternate has materially more voiced words.
                # Scientific tokens are reconciled later by the contract QA, never by this heuristic.
                primary_words = [w for s in result["segments"] for w in s.get("words", [])]
                alternate_words = [w for s in alternate["segments"] for w in s.get("words", [])]
                primary_text = " ".join(s.get("text", "") for s in result.get("segments", []))
                alternate_text = " ".join(s.get("text", "") for s in alternate.get("segments", []))
                primary_score = sum(float(w.get("probability", 0.0)) for w in primary_words) / max(1, len(primary_words))
                alternate_score = sum(float(w.get("probability", 0.0)) for w in alternate_words) / max(1, len(alternate_words))
                result["alternate_asr"] = {"engine": "faster-whisper", "beam_size": 8,
                                            "min_silence_duration_ms": 900,
                                            "word_count": len(alternate_words),
                                            "mean_probability": round(alternate_score, 4),
                                            "agreement_word_count": len(primary_words),
                                            "selected": alternate_score > primary_score + 0.03 and len(alternate_words) >= len(primary_words) - 3}
                if result["alternate_asr"]["selected"]:
                    result = alternate
                    result["alternate_asr"] = {"engine": "faster-whisper", "selected": True,
                                                "beam_size": 8, "min_silence_duration_ms": 900}
                result["protected_evidence"] = protected_evidence(
                    primary_text, alternate_text)
            wx = _whisperx_decode(prepared_audio, language, model_size, device) if os.environ.get("WHISPERX_EVIDENCE", "1") != "0" else None
            if wx is not None:
                result["whisperx_evidence"] = {"text": wx.get("text", ""), "segment_count": len(wx.get("segments", [])),
                                                "engine": "whisperx", "used_for_replacement": False}
            if temp_audio_dir:
                shutil.rmtree(temp_audio_dir, ignore_errors=True)
            result["quality"] = {"repetition_loops": loops, "retried_without_context": retried, "prompted": bool(prompt),
                                 "hotwords": bool(hotwords), "model": str(model_size), "window_seconds": window or 30,
                                 "dropped_hallucinated_segments": result.get("dropped_segments", 0),
                                 "foreign_script_removed": result.get("foreign_script_removed", 0),
                                 "tail_rescued_words": result.get("tail_rescued_words", 0), "condition_on_previous_text": condition}
            result["audio_preparation"] = audio_preparation
        except ImportError as import_error:
            if "faster_whisper" not in str(getattr(import_error, "name", "") or import_error):
                raise          # a broken dependency inside faster-whisper (e.g. a torch DLL) must not hide behind a silent fallback
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
