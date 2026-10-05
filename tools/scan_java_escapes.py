"""Finds Java string literals that contain a single-backslash regex escape (\\s \\d \\w \\p \\b ...), which Java treats as a plain
space/backspace or rejects. Text blocks (triple quotes) are skipped."""
import sys
from pathlib import Path

SUSPECT = set("sdwSDWpPb")
found = 0
for path in Path(sys.argv[1]).rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    i, line = 0, 1
    while i < len(text):
        c = text[i]
        if c == "\n":
            line += 1
        if text.startswith('"""', i):                        # text block: skip to its end
            end = text.find('"""', i + 3)
            line += text.count("\n", i, end)
            i = end + 3
            continue
        if text.startswith("//", i):
            i = text.find("\n", i)
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            line += text.count("\n", i, end)
            i = end + 2
            continue
        if c == "'" :                                         # char literal
            i += 3 if text[i + 1] != "\\" else 4
            continue
        if c == '"':
            j = i + 1
            while j < len(text) and text[j] != '"':
                if text[j] == "\\":
                    if text[j + 1] in SUSPECT:
                        found += 1
                        print(f"{path}:{line}: \\{text[j + 1]} in {text[i:i + 70]!r}")
                    j += 2
                    continue
                if text[j] == "\n":
                    break
                j += 1
            i = j + 1
            continue
        i += 1
print(found, "suspicious escapes")
