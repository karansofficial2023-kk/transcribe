import io
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import translate_text as t  # noqa: E402


class FakeTranslator:
    def translate(self, texts, target):
        if target == "xx":
            raise ValueError("unsupported")
        return [f"{target}:{text}" for text in texts]

    def unload(self):
        self.unloaded = True


class TagTest(unittest.TestCase):
    def test_tags(self):
        self.assertEqual(t.tag_for("ta"), "<2ta>")
        self.assertEqual(t.tag_for("te-IN"), "<2te>")
        self.assertEqual(t.tag_for("HI"), "<2hi>")
        with self.assertRaises(ValueError):
            t.tag_for("fr")
        with self.assertRaises(ValueError):
            t.tag_for(None)


class BatchingTest(unittest.TestCase):
    def test_order_and_regrouping(self):
        texts = ["ccc", "a", "bb", "dddd"]
        self.assertEqual(t.order_by_length(texts), [1, 2, 0, 3])

    def test_batches_respect_size_and_cover_every_index_once(self):
        texts = ["x" * n for n in (500, 900, 700, 100, 1700, 50, 60, 70)]
        groups = list(t.batches(t.order_by_length(texts), texts, max_chars=1000, max_items=3))
        flat = [i for g in groups for i in g]
        self.assertEqual(sorted(flat), list(range(len(texts))))
        self.assertTrue(all(len(g) <= 3 for g in groups))
        for g in groups:
            if len(g) > 1:
                self.assertLessEqual(sum(len(texts[i]) for i in g), 1000)

    def test_a_single_oversized_text_still_gets_its_own_batch(self):
        groups = list(t.batches([0], ["x" * 5000], max_chars=1000))
        self.assertEqual(groups, [[0]])


class ProtocolTest(unittest.TestCase):
    def run_worker(self, lines):
        out = io.StringIO()
        fake = FakeTranslator()
        t.serve(fake, io.StringIO("\n".join(lines) + "\n"), out)
        return [json.loads(x) for x in out.getvalue().splitlines()], fake

    def test_round_trip_keeps_order_and_unicode(self):
        answers, fake = self.run_worker([json.dumps({"target": "ta", "texts": ["one", "தமிழ்"]}, ensure_ascii=False)])
        self.assertEqual(answers, [{"translations": ["ta:one", "ta:தமிழ்"]}])

    def test_an_error_is_reported_and_the_worker_keeps_serving(self):
        answers, _ = self.run_worker([json.dumps({"target": "xx", "texts": ["a"]}), "not json",
                                      json.dumps({"target": "te", "texts": ["b"]})])
        self.assertIn("error", answers[0])
        self.assertIn("error", answers[1])
        self.assertEqual(answers[2], {"translations": ["te:b"]})

    def test_quit_unloads_the_model(self):
        answers, fake = self.run_worker([json.dumps({"command": "quit"})])
        self.assertEqual(answers, [])
        self.assertTrue(fake.unloaded)


if __name__ == "__main__":
    unittest.main()
