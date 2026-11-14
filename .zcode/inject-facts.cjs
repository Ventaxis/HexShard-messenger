// Adds grounded Ventaxis facts to gemini-assistant personas (run via node file).
const fs = require("fs");
const p = "supabase/functions/gemini-assistant/index.ts";
let t = fs.readFileSync(p, "utf8");
const anchor = "// Model routing: per-persona parameters with fallback chain (primary first).";
if (!t.includes(anchor)) { console.log("anchor missing"); process.exit(1); }
const facts = [
  "// Grounded brand knowledge: personas answer about Ventaxis from these facts",
  "// only, never invent (owner report: invented a car company, iOS/web builds).",
  "const VENTAXIS_FACTS = `",
  "",
  "Facts about Ventaxis Corp and Ventaxis (answer ONLY from these; if something is not covered, say you do not know - never invent):",
  '- "Ventaxis" is the personal handle of HexShard creator - the founder and operator of this service.',
  "- Ventaxis Corp is the small independent project label behind HexShard Messenger. It is NOT a large corporation and does NOT manufacture cars, devices or any hardware.",
  "- HexShard Messenger is Android-only today. There is NO iOS app, NO web client and NO official desktop build - never claim otherwise.",
  '- If asked "ventaxis крутой?" or similar, answer in your persona honestly and playfully, without inventing facts about Ventaxis.',
  "`;",
  "",
].join("\n");
t = t.replace(anchor, facts + anchor);
// Append the facts to both persona prompts.
t = t.replace(
  'If web search context is provided, ground your answer in it and mention what you used.`;',
  'If web search context is provided, ground your answer in it and mention what you used.` + VENTAXIS_FACTS;'
);
t = t.replace(
  '- If web search context is provided, ground your answer in it.`;',
  '- If web search context is provided, ground your answer in it.` + VENTAXIS_FACTS;'
);
fs.writeFileSync(p, t);
console.log("facts injected:", (t.match(/VENTAXIS_FACTS/g) || []).length, "references");
