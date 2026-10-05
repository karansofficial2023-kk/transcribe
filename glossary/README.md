# Glossaries (teacher-maintained spelling corrections)

Speech recognition mis-spells subject terms, most often in Indic scripts. A glossary is a plain JSON map
`{"heard": "correct"}` applied **before** paraphrasing (whole-word, case-insensitive, combining-mark aware). The correct
spellings are also passed to the recogniser as hotwords, so the next recording of the same terms is recognised better.

Sources, later ones override earlier ones:

| File | Scope |
|------|-------|
| `glossary/<lang>.json` (this folder) | every lesson in that language, e.g. `ta.json`, `te.json`, `hi.json` |
| `<materials dir>/glossary.json` | every lesson that uses that materials folder |
| `<materials dir>/<lesson>_glossary.json` | one lesson |

**No entries are shipped.** Earlier drafts contained guessed Hindi corrections; they were removed because an unreviewed
correction can damage correct text. Entries only come from a person who knows the language.

## Workflow
1. Run the lesson once. Next to `<lesson>_transcript.json` the recogniser writes `<lesson>_glossary_candidates.json`:
   the words it was least sure about, each with how often it heard it and an example sentence.
2. A teacher/subject expert reads the list and copies the genuinely wrong words into one of the files above with the
   correct spelling. Words that are correct are simply ignored.
3. Re-run (or just re-run the paraphrase step): corrections apply automatically.
