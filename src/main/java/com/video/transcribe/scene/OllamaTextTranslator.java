package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.LanguageSupport.Language;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.transcription.Glossary;

/** Local-model translation of school-science text (Qwen via Ollama), one id-keyed batch per call. */
public final class OllamaTextTranslator implements StoryboardTranslator.TextTranslator {
    private static final JsonObject SCHEMA = JsonParser.parseString("""
        {"type":"object","properties":{"translations":{"type":"array","items":{"type":"object",
          "properties":{"id":{"type":"integer"},"text":{"type":"string"}},"required":["id","text"]}}},
         "required":["translations"]}""").getAsJsonObject();

    private final OllamaClient ollama;
    private final Glossary glossary;

    public OllamaTextTranslator(OllamaClient ollama, Glossary glossary) {
        this.ollama = ollama;
        this.glossary = glossary;
    }

    @Override
    public List<String> translate(List<String> texts, Language source, Language target) throws Exception {
        JsonArray items = new JsonArray();
        for (int i = 0; i < texts.size(); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("id", i);
            item.addProperty("text", texts.get(i));
            items.add(item);
        }
        JsonObject request = new JsonObject();
        request.addProperty("from", source.name());
        request.addProperty("to", target.name());
        request.add("items", items);
        int budget = Math.min(16000, 900 + texts.stream().mapToInt(text -> 120 + text.length() * 6).sum());
        JsonObject response = JsonParser.parseString(ollama.generateStructured(systemPrompt(source, target), request.toString(), SCHEMA, budget))
            .getAsJsonObject();

        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) out.add("");
        for (JsonElement element : response.getAsJsonArray("translations")) {
            JsonObject entry = element.getAsJsonObject();
            int id = entry.get("id").getAsInt();
            if (id >= 0 && id < out.size()) {
                String text = entry.get("text").getAsString().trim();
                out.set(id, glossary == null ? text : glossary.apply(text));
            }
        }
        return out;
    }

    static String systemPrompt(Language source, Language target) {
        return "You are a professional translator of school science lessons from " + source.name() + " into " + target.name() + " ("
            + target.nativeName() + "). Translate every item faithfully. Rules:\n"
            + "1. Keep exactly as written: digits and numbers, units written as symbols, chemical formulas and symbols (H2O, CO2, Ag+, Na), "
            + "mathematical expressions and variable names.\n"
            + "2. Use the standard " + target.name() + " school-textbook term for scientific words. If no standard term exists, keep the "
            + "English term.\n"
            + "3. Do not add, remove or explain anything. Do not merge or split items.\n"
            + "4. The text is spoken narration or short on-screen text: keep it natural and about as long as the original; labels and "
            + "headings stay short.\n"
            + "5. Write in the " + target.name() + " script. Return JSON {\"translations\":[{\"id\":<same id>,\"text\":\"<translation>\"}]} "
            + "with one entry per item."
            + "\n6. Diagram labels and points (AB, CD, B1, S2), bond notation (C=O, C≡N, -COOH) and IUPAC suffixes (-one, -al, -ol) are "
            + "copied exactly as written. Never spell them in " + target.name() + " letters (CD must not become a transliteration) and "
            + "never replace a suffix such as -one with a functional group."
            + ("hi".equals(target.code()) ? HINDI_STEM_GLOSSARY : "");
    }

    /** Standard NCERT physics/chemistry wording that literal translation gets wrong (wrong forms seen in real dubbing reports). */
    static final String HINDI_STEM_GLOSSARY = "\n7. Mandatory Hindi science terms:\n"
        + "   - arm AB / arm CD -> भुजा AB / भुजा CD (never हाथ AB)\n"
        + "   - middle finger -> मध्यमा उंगली (never मध्य अंगूठी); first finger / forefinger -> तर्जनी उंगली; thumb -> अंगूठा\n"
        + "   - slip ring -> स्लिप रिंग (never स्लीप अंगूठी); brush -> ब्रश; coil -> कुंडली; commutator -> कम्यूटेटर\n"
        + "   - consists of / is made of -> से मिलकर बना होता है (never द्वारा बनाया गया); stationary part -> स्थिर भाग\n"
        + "   - clockwise -> घड़ी की दिशा में; anticlockwise -> घड़ी की विपरीत दिशा में\n"
        + "   - electric current -> विद्युत धारा; alkane -> ऐल्केन; carboxylic acid -> कार्बोक्सिलिक अम्ल";
}
