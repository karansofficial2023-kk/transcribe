import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import whisper_transcribe as w  # noqa: E402


class PromptTest(unittest.TestCase):
    def test_every_supported_language_has_a_prompt_in_its_own_script(self):
        for code in ("hi", "mr", "ta", "te", "ml", "kn", "bn", "gu", "pa", "ur"):
            prompt = w.initial_prompt(code)
            self.assertTrue(prompt, code)
            self.assertFalse(prompt.isascii(), code)
        self.assertTrue(w.initial_prompt("en").isascii())
        self.assertEqual(w.initial_prompt("ta-IN"), w.initial_prompt("ta"))
        self.assertIsNone(w.initial_prompt("xx"))
        self.assertIsNone(w.initial_prompt(None))

    def test_prompts_end_sentences_with_the_languages_own_terminator(self):
        for code in ("hi", "bn", "pa"):
            self.assertIn("।", w.initial_prompt(code), code)
        self.assertIn("۔", w.initial_prompt("ur"))


class LoopTest(unittest.TestCase):
    def test_detects_runs_of_identical_segments(self):
        self.assertEqual(w.repetition_loops(["a b", "x", "x", "x", "x", "y"]), 1)
        self.assertEqual(w.repetition_loops(["x", "x", "y", "x", "x"]), 0)
        self.assertEqual(w.repetition_loops(["Same!", "same", "SAME."]), 1)       # punctuation and case do not hide a loop
        self.assertEqual(w.repetition_loops([]), 0)

    def test_two_separate_loops(self):
        self.assertEqual(w.repetition_loops(["a", "a", "a", "b", "c", "c", "c"]), 2)


class CandidatesTest(unittest.TestCase):
    SEGMENTS = [{"text": "the anod is positive", "words": [
        {"word": "the", "probability": 0.99}, {"word": "anod", "probability": 0.31}, {"word": "is", "probability": 0.99},
        {"word": "12", "probability": 0.2}, {"word": "positive,", "probability": 0.9}]},
        {"text": "anod again", "words": [{"word": "Anod", "probability": 0.45}, {"word": "again", "probability": 0.95}]}]

    def test_lists_doubtful_words_once_with_their_worst_score(self):
        found = w.uncertain_words(self.SEGMENTS)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["count"], 2)
        self.assertEqual(found[0]["min_probability"], 0.31)

    def test_writes_a_candidates_file_next_to_the_transcript(self):
        with tempfile.TemporaryDirectory() as folder:
            transcript = Path(folder) / "lesson_transcript.json"
            target = w.write_candidates(str(transcript), "en", self.SEGMENTS)
            self.assertEqual(target.name, "lesson_glossary_candidates.json")
            self.assertEqual(json.loads(target.read_text(encoding="utf-8"))["candidates"][0]["heard"], "anod")
        self.assertIsNone(w.write_candidates(None, "en", self.SEGMENTS))


class IndicWordIntegrityTest(unittest.TestCase):
    def test_trim_keeps_vowel_signs_and_viramas(self):
        self.assertEqual(w._trim("है,"), "है")                     # Hindi 'hai' loses nothing but the comma
        self.assertEqual(w._trim("“பார்ப்போம்”."), "பார்ப்போம்")
        self.assertEqual(w._trim("...word!?"), "word")

    def test_candidates_keep_whole_indic_words(self):
        word = "பார்ப்போம்,"
        found = w.uncertain_words([{"text": "x", "words": [{"word": word, "probability": 0.3}]}])
        self.assertEqual(found[0]["heard"], "பார்ப்போம்")

    def test_loops_distinguish_words_that_differ_only_by_a_vowel_sign(self):
        same = ["கால்"] * 3                       # three identical segments
        different = ["கால்", "காலி", "காலு"]
        self.assertEqual(w.repetition_loops(same), 1)
        self.assertEqual(w.repetition_loops(different), 0)


class HallucinationTest(unittest.TestCase):
    def seg(self, text, start, end):
        return {"id": 0, "text": text, "start": start, "end": end, "words": []}

    def test_a_zero_length_sentence_after_the_speech_is_dropped(self):
        segments = [self.seg("ஒளிச்சேர்க்கை என்பது பச்சை தாவரங்கள் உணவு தயாரிக்கும் செயல்முறை", 0.0, 20.5),
                    self.seg("இந்தியாவில் உள்ள பல்வேறு நாடுகளின் தலைவர்களும் இதில் கலந்து கொண்டனர்", 53.3, 53.3)]
        kept, dropped = w.drop_impossible_segments(segments)
        self.assertEqual(dropped, 1)
        self.assertEqual(len(kept), 1)
        self.assertEqual(kept[0]["id"], 0)

    def test_real_speech_rates_are_kept(self):
        # 590 letters in 53 s (Tamil lesson) and 500 letters in 33 s are both ordinary speech
        self.assertFalse(w.impossible_segment(self.seg("அ" * 590, 0.0, 53.0)))
        self.assertFalse(w.impossible_segment(self.seg("a" * 500, 0.0, 33.0)))

    def test_far_too_much_text_for_the_time_is_dropped(self):
        self.assertTrue(w.impossible_segment(self.seg("a" * 200, 10.0, 12.0)))

    def test_short_fragments_and_empty_text_are_not_judged(self):
        self.assertFalse(w.impossible_segment(self.seg("ok", 5.0, 5.0)))
        self.assertFalse(w.impossible_segment(self.seg("", 5.0, 5.0)))


class WindowTest(unittest.TestCase):
    def test_validated_and_default_windows(self):
        self.assertEqual(w.window_seconds("te"), 8)
        self.assertEqual(w.window_seconds("ta-IN"), 22)
        self.assertEqual(w.window_seconds("ml"), w.DEFAULT_INDIC_WINDOW)
        self.assertEqual(w.window_seconds("hi"), w.DEFAULT_INDIC_WINDOW)
        self.assertIsNone(w.window_seconds("en"))
        self.assertIsNone(w.window_seconds(None))

    def test_every_window_keeps_dense_scripts_under_the_decoder_cap(self):
        tokens_per_second = {"te": 22.2, "ta": 10.9}          # measured with the Whisper tokenizer on lesson narration
        for code, rate in tokens_per_second.items():
            self.assertLess(w.window_seconds(code) * rate, 448 * 0.75, code)


class SpecialistTest(unittest.TestCase):
    def test_only_existing_folders_are_kept(self):
        with tempfile.TemporaryDirectory() as folder:
            found = w.parse_specialists(f"ta={folder}; te={folder}/missing ;; bad; HI={folder}")
            self.assertEqual(sorted(found), ["hi", "ta"])
            self.assertEqual(found["ta"], folder)
        self.assertEqual(w.parse_specialists(None), {})
        self.assertEqual(w.parse_specialists(""), {})


if __name__ == "__main__":
    unittest.main()
