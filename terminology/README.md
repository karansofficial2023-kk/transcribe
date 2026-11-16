# Translation terminology

Hindi subject dictionaries are stored in `terminology/hi/*.json`. Each key is an English source term; each value names the
preferred Hindi school term, alternate Hindi/loanword forms that should be normalized, and an optional subject label.
The source narration must contain the key as a whole word before an alternate translation is changed. This reduces
false substitutions in unrelated sentences.

Add reviewed entries to a subject file, or override/add terms for one lesson in the storyboard materials directory as
`<lesson>_terminology_<language>.json`, for example `Acid-base Indicators_terminology_hi.json`. Entries take effect on
the next Transcribe run. This is a curated consistency glossary, not automatic subject classification: dictionaries are
loaded for the target language and source-term matching decides whether a term applies.

Example:

```json
{
  "virus": {"preferred": "विषाणु", "aliases": ["वायरस"], "subject": "biology"}
}
```
