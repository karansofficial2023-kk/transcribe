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
import re
import json
import sys
from pathlib import Path

# MADLAD selects the output language with a "<2xx>" prefix; xx is the ISO 639-1 code.
SUPPORTED = {"en", "hi", "mr", "ta", "te", "ml", "kn", "bn", "gu", "pa", "or", "ur"}


# Formulas, bonds, functional groups and diagram labels (AB, B1, S2, C=O, -COOH, H2SO4) must reach the output untouched. MADLAD
# otherwise transliterates them ("CD" -> "सीडी") or invents text around them. They are swapped for "[n]" placeholders, which the
# model keeps in place and translates around, and restored afterwards (checked on the real model: "आर्म [1] हमेशा स्लिप रिंग [2] ...").
_ELEMENTS = set("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y Zr Nb Mo "
                "Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os Ir Pt Au Hg "
                "Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U".split())
_CAPS_WORDS = {"HIS", "HAS", "ALL", "ARE", "NOT", "THE", "AND", "BUT", "FOR", "CAN", "IT", "IS", "OK", "HE", "SO", "NO", "US", "IN", "AS", "AT", "BE", "OR", "AM", "AN", "ON", "TO", "OF", "IF", "ID", "TV", "UK"}
_PROTECTED = re.compile(
    r"(?<![A-Za-z0-9\[])(?:"
    r"-?(?:[A-Z][a-z]?\d*)+(?:[=≡-](?:[A-Z][a-z]?\d*)+)+"                 # C=O, C≡N, R-OH, O-N-E
    r"|-(?:COOH|CHO|OH|NH2|NO2)"                                               # -COOH
    r"|(?:[A-Z][a-z]?\d*|\((?:[A-Z][a-z]?\d*)+\)\d*)+(?:\^?\d*[+-])?"          # H2SO4, NaCl, B1, ABCD, Ca2+
    r")(?![A-Za-z0-9\]])")
_PLACEHOLDER = re.compile(r"\[(\d+)\]")


def _worth_protecting(token):
    core = token.lstrip("-")
    if any(op in token for op in "=≡") or token.startswith("-") or re.search(r"\d", core):
        return True
    if core.upper() in _CAPS_WORDS:
        return False
    if core.isupper():
        return len(core) >= 2
    chunks = re.findall(r"[A-Z][a-z]?", core)
    return len(chunks) >= 2 and "".join(chunks) == core and all(chunk in _ELEMENTS for chunk in chunks)


def mask_protected(text):
    """Replace protected tokens by [1], [2] ... Returns (masked_text, [tokens])."""
    tokens = []

    def swap(match):
        token = match.group()
        if not _worth_protecting(token):
            return token
        tokens.append(token)
        return f"[{len(tokens)}]"

    return _PROTECTED.sub(swap, text), tokens


def unmask_protected(text, tokens):
    """Put the original tokens back. Returns (text, ok); ok is False when a placeholder was lost or duplicated."""
    found = [int(n) for n in _PLACEHOLDER.findall(text)]
    if sorted(found) != list(range(1, len(tokens) + 1)):
        return text, False
    return _PLACEHOLDER.sub(lambda m: tokens[int(m.group(1)) - 1], text), True


def tag_for(target):
    code = (target or "").split("-")[0].lower()
    if code not in SUPPORTED:
        raise ValueError(f"unsupported target language {target!r}")
    return f"<2{code}>"


def order_by_length(texts):
    """Indices that sort texts by length, so padding inside a batch is small; results are restored to input order."""
    return sorted(range(len(texts)), key=lambda i: len(texts[i]))


def batches(indices, texts, max_chars=600, max_items=4):
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
        # Transformers 5 renamed torch_dtype to dtype; keep production logs clean on the pinned runtime.
        model = AutoModelForSeq2SeqLM.from_pretrained(self.model_dir, dtype=dtype, tie_word_embeddings=False)
        restore_shared_embedding(model, self.model_dir)
        self.model = model.to(self.device).eval()
        self.torch = torch

    def translate(self, texts, target):
        """Translate with formulas/labels protected; a line whose placeholders were lost is translated again unmasked."""
        masked = [mask_protected(text) for text in texts]
        results = self._translate_raw([m[0] for m in masked], target)
        unsafe = []
        for index, (_text, tokens) in enumerate(masked):
            if not tokens:
                continue
            restored, ok = unmask_protected(results[index], tokens)
            if ok:
                results[index] = restored
            else:
                unsafe.append(index)
        if unsafe:
            print(f"MADLAD {target}: {len(unsafe)} line(s) lost a protected token; retranslating them unmasked",
                  file=sys.stderr, flush=True)
            for index, text in zip(unsafe, self._translate_raw([texts[i] for i in unsafe], target)):
                results[index] = text
        return results

    def _translate_raw(self, texts, target):
        self.load()
        tag = tag_for(target)
        results = [""] * len(texts)
        completed = 0
        for group in batches(order_by_length(texts), texts):
            prompts = [f"{tag} {texts[i]}" for i in group]
            inputs = self.tokenizer(prompts, return_tensors="pt", padding=True, truncation=True, max_length=512).to(self.device)
            longest = int(inputs["input_ids"].shape[1])
            with self.torch.no_grad():
                generated = self.model.generate(**inputs, max_new_tokens=min(320, max(64, int(longest * 2.0))), num_beams=4,
                                                early_stopping=True)
            for index, text in zip(group, self.tokenizer.batch_decode(generated, skip_special_tokens=True)):
                results[index] = text.strip()
            completed += len(group)
            print(f"MADLAD {target}: {completed}/{len(texts)} lines translated", file=sys.stderr, flush=True)
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
