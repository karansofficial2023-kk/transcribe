#!/usr/bin/env python3
"""
Offline machine translation worker (MADLAD-400 3B, Apache-2.0) used by the Java storyboard translator.

Protocol (one JSON object per line on stdin / stdout, UTF-8):
    -> {"target": "ta", "texts": ["...", "..."]}
    <- {"translations": ["...", "..."]}          or   {"error": "..."}
The model is loaded once, on the first request, and kept for the lifetime of the process.

    python translate_text.py --model-dir D:/AI/models/madlad400-3b-mt [--device cuda]
"""

import argparse
import json
import sys
from pathlib import Path

# MADLAD selects the output language with a "<2xx>" prefix; xx is the ISO 639-1 code.
SUPPORTED = {"en", "hi", "mr", "ta", "te", "ml", "kn", "bn", "gu", "pa", "or", "ur"}


def tag_for(target):
    code = (target or "").split("-")[0].lower()
    if code not in SUPPORTED:
        raise ValueError(f"unsupported target language {target!r}")
    return f"<2{code}>"


def order_by_length(texts):
    """Indices that sort texts by length, so padding inside a batch is small; results are restored to input order."""
    return sorted(range(len(texts)), key=lambda i: len(texts[i]))


def batches(indices, texts, max_chars=1800, max_items=12):
    """Groups of indices whose combined text stays small enough for one forward pass."""
    group, size = [], 0
    for index in indices:
        length = len(texts[index])
        if group and (size + length > max_chars or len(group) >= max_items):
            yield group
            group, size = [], 0
        group.append(index)
        size += length
    if group:
        yield group


def restore_shared_embedding(model, model_dir):
    """The checkpoint stores the embedding table once ('decoder.embed_tokens.weight'; its metadata lists 'shared.weight' and
    'encoder.embed_tokens.weight' as aliases). Newer transformers releases do not apply that alias and would leave the shared
    embedding randomly initialised (garbage output), so it is copied in explicitly."""
    from safetensors import safe_open

    with safe_open(str(Path(model_dir) / "model.safetensors"), framework="pt") as handle:
        table = handle.get_tensor("decoder.embed_tokens.weight")
    for module in (model.shared, model.encoder.embed_tokens, model.decoder.embed_tokens):
        module.weight.data.copy_(table.to(module.weight.dtype))


class Translator:
    def __init__(self, model_dir, device):
        self.model_dir, self.device = model_dir, device
        self.model = self.tokenizer = None

    def load(self):
        if self.model is not None:
            return
        import torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

        print(f"Loading translation model from {self.model_dir}", file=sys.stderr, flush=True)
        self.tokenizer = AutoTokenizer.from_pretrained(self.model_dir)
        # bfloat16: T5-family models overflow in float16; the RTX 30-series supports bfloat16 natively
        dtype = torch.bfloat16 if self.device == "cuda" else torch.float32
        model = AutoModelForSeq2SeqLM.from_pretrained(self.model_dir, torch_dtype=dtype, tie_word_embeddings=False)
        restore_shared_embedding(model, self.model_dir)
        self.model = model.to(self.device).eval()
        self.torch = torch

    def translate(self, texts, target):
        self.load()
        tag = tag_for(target)
        results = [""] * len(texts)
        for group in batches(order_by_length(texts), texts):
            prompts = [f"{tag} {texts[i]}" for i in group]
            inputs = self.tokenizer(prompts, return_tensors="pt", padding=True, truncation=True, max_length=512).to(self.device)
            longest = int(inputs["input_ids"].shape[1])
            with self.torch.no_grad():
                generated = self.model.generate(**inputs, max_new_tokens=min(768, max(48, int(longest * 2.5))), num_beams=4,
                                                early_stopping=True)
            for index, text in zip(group, self.tokenizer.batch_decode(generated, skip_special_tokens=True)):
                results[index] = text.strip()
        return results

    def unload(self):
        self.model = self.tokenizer = None
        try:
            import gc
            import torch

            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass


def serve(translator, stdin, stdout):
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            if request.get("command") == "quit":
                break
            answer = {"translations": translator.translate(request["texts"], request["target"])}
        except Exception as error:          # one bad request must not kill the worker
            answer = {"error": f"{type(error).__name__}: {error}"}
        stdout.write(json.dumps(answer, ensure_ascii=False) + "\n")
        stdout.flush()
    translator.unload()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Offline translation worker (MADLAD-400)")
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--device", default="cuda")
    arguments = parser.parse_args()
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    serve(Translator(arguments.model_dir, arguments.device), sys.stdin, sys.stdout)
