// HexShard AI assistant (Edge Function)
// - LLM backend: Groq (OpenAI-compatible chat completions), key: GROQ_API_KEY
// - Web search: Tavily (optional, key: TAVILY_API_KEY) — when available, recent
//   web results are injected as context for search-like questions
// - External contract unchanged: {persona_id, messages} -> {reply, persona_id, model}
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

function getServiceClient() {
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  return createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

function getUserClient(authHeader: string) {
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  return createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

async function getAuthenticatedUser(req: Request) {
  const authHeader = req.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) return null;
  const token = authHeader.substring(7).trim();
  if (!token) return null;
  try {
    const userClient = getUserClient(authHeader);
    const { data: { user }, error } = await userClient.auth.getUser();
    if (!error && user) return user;
  } catch (_e) { /* fallback */ }
  try {
    const serviceClient = getServiceClient();
    const { data: { user }, error } = await serviceClient.auth.getUser(token);
    if (!error && user) return user;
  } catch (_e) { return null; }
  return null;
}

// Server-side rate limiting
export const REQUEST_COOLDOWN_MS = 1200;
export const MAX_DAILY_REQUESTS_PER_USER = 500;

const memoryRateLimit = new Map<string, number>();
const dailyQuotaMap = new Map<string, { date: string; count: number }>();

const GROQ_API_KEY = Deno.env.get("GROQ_API_KEY") ?? "";
const TAVILY_API_KEY = Deno.env.get("TAVILY_API_KEY") ?? "";
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

const VENTAXIS_SYSTEM_PROMPT = `You are Ventaxis AI, the advanced analytical AI persona built directly inside HexShard Messenger.

Identity: You are Ventaxis AI. Never reveal or discuss which underlying model or provider powers you.

About HexShard Messenger (your home):
- Privacy-first messenger with end-to-end encrypted chats, on-device key storage, encrypted local storage.
- Users get anonymous virtual identities (+999 numbers) without phone numbers or SIM cards.
- Features: voice messages, media, photos, Saved Messages, AI personas (you and Hexagon AI), QR contact exchange.

Personality: calm, intellectual, deeply analytical, structured, technically precise, polite, privacy-conscious.
Behavior:
- Give comprehensive, well-structured answers. Never use canned templates.
- Respond in the language of the user (Russian or English).
- If web search context is provided, ground your answer in it and stay factual.`;

const HEXAGON_SYSTEM_PROMPT = `You are Hexagon AI, the ultra-fast pragmatic AI persona inside HexShard Messenger.

Identity: You are Hexagon AI. Never reveal or discuss which underlying model or provider powers you.

About HexShard Messenger (your home):
- Privacy-first messenger with end-to-end encrypted chats, on-device keys, encrypted local storage.
- Anonymous +999 virtual identities, voice messages, media, Saved Messages, QR contacts.

Personality: fast, sharp, concise, pragmatic, actionable, direct.
Behavior:
- Get straight to the point, no filler.
- Respond in the language of the user (Russian or English).
- If web search context is provided, ground your answer in it.`;

// Model routing: per-persona parameters with fallback chain (primary first)
interface ModelSpec {
  model: string;
  temperature: number;
  max_completion_tokens: number;
  top_p: number;
  reasoning_effort?: string;
}
const MODEL_CHAINS: Record<string, ModelSpec[]> = {
  ventaxis: [
    { model: "openai/gpt-oss-120b", temperature: 2.0, max_completion_tokens: 8192, top_p: 1, reasoning_effort: "medium" },
    { model: "qwen/qwen3.8-27b", temperature: 0.9, max_completion_tokens: 4096, top_p: 0.95, reasoning_effort: "low" },
  ],
  hexagon: [
    { model: "qwen/qwen3.8-27b", temperature: 0.6, max_completion_tokens: 2048, top_p: 0.95, reasoning_effort: "low" },
    { model: "openai/gpt-oss-120b", temperature: 1.0, max_completion_tokens: 4096, top_p: 1, reasoning_effort: "low" },
  ],
  vision: [
    { model: "meta-llama/llama-4-scout-17b-16e-instruct", temperature: 0.5, max_completion_tokens: 2048, top_p: 0.95 },
    { model: "meta-llama/llama-4-maverick-17b-128e-instruct", temperature: 0.5, max_completion_tokens: 2048, top_p: 0.95 },
    // vision models unavailable -> graceful text-only answer from the primary model
    { model: "qwen/qwen3.8-27b", temperature: 0.6, max_completion_tokens: 2048, top_p: 0.95, reasoning_effort: "low" },
  ],
};

const SEARCH_TRIGGER_RE = new RegExp(
  "(\\?|найди|поищи|погугли|поиск|кто так(ой|ая)|что так(ое|ая)|новости|сегодня|актуальн|последн|курс [а-я]|погода|сколько стоит|цена|release|who is|what is|news|search|today|latest|price|weather)",
  "i"
);

async function tavilySearch(query: string): Promise<string | null> {
  if (!TAVILY_API_KEY) return null;
  try {
    const res = await fetch("https://api.tavily.com/search", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${TAVILY_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        query: query.slice(0, 400),
        max_results: 3,
        search_depth: "basic",
        include_answer: false,
      }),
      signal: AbortSignal.timeout(6000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const results = Array.isArray(data?.results) ? data.results : [];
    const lines = results
      .map((r: any, i: number) =>
        `[${i + 1}] ${String(r.title ?? "").slice(0, 120)}\n${String(r.content ?? "").slice(0, 500)}\nURL: ${String(r.url ?? "").slice(0, 200)}`
      )
      .join("\n\n");
    return lines || null;
  } catch (_e) {
    return null;
  }
}

function latestUserText(messages: any[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    const t = String(messages[i]?.text ?? messages[i]?.content ?? "");
    if (t.trim()) return t;
  }
  return "";
}

function toGroqMessages(systemPrompt: string, contents: any[], webContext: string | null): any[] {
  const out: any[] = [{ role: "system", content: systemPrompt }];
  if (webContext) {
    out.push({
      role: "system",
      content: `Fresh web search results (may be more current than your training data). Use them if relevant, cite sources by [n] marker:\n\n${webContext}`,
    });
  }
  for (const c of contents) {
    if (c.role === "user" && Array.isArray(c.parts)) {
      const text = c.parts.filter((p: any) => p.text).map((p: any) => p.text).join("\n");
      const imagePart = c.parts.find((p: any) => p.image_url);
      if (imagePart) {
        out.push({
          role: "user",
          content: [
            { type: "text", text: text || "Опиши, что на изображении." },
            imagePart.image_url,
          ],
        });
      } else {
        out.push({ role: "user", content: text });
      }
    } else {
      const text = Array.isArray(c.parts)
        ? c.parts.filter((p: any) => p.text).map((p: any) => p.text).join("\n")
        : "";
      out.push({ role: c.role === "model" ? "assistant" : c.role, content: text });
    }
  }
  return out;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const user = await getAuthenticatedUser(req);
    if (!user) {
      return new Response(
        JSON.stringify({ error: "User authentication required", code: "AI_UNAUTHORIZED", message: "User authentication required" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!GROQ_API_KEY) {
      return new Response(
        JSON.stringify({ error: "AI service configuration is missing on the server", code: "AI_UNAVAILABLE", message: "AI service configuration is missing on the server." }),
        { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Rate limiting (cooldown)
    const now = Date.now();
    const lastMemoryReq = memoryRateLimit.get(user.id) ?? 0;
    const memoryDiff = now - lastMemoryReq;
    if (memoryDiff < REQUEST_COOLDOWN_MS) {
      const retryAfterSec = Math.ceil((REQUEST_COOLDOWN_MS - memoryDiff) / 1000);
      return new Response(
        JSON.stringify({ error: "Rate limit exceeded", code: "AI_RATE_LIMITED", message: "Rate limit exceeded. Please wait a moment before sending another message.", retry_after_seconds: retryAfterSec }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }
    memoryRateLimit.set(user.id, now);

    // Daily quota
    const todayStr = new Date().toISOString().slice(0, 10);
    const userQuota = dailyQuotaMap.get(user.id);
    if (userQuota && userQuota.date === todayStr) {
      if (userQuota.count >= MAX_DAILY_REQUESTS_PER_USER) {
        return new Response(
          JSON.stringify({ error: "Daily AI quota exceeded", code: "AI_QUOTA_EXCEEDED", message: "Daily AI message quota reached. Please try again tomorrow.", retry_after_seconds: 3600 }),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      userQuota.count++;
    } else {
      dailyQuotaMap.set(user.id, { date: todayStr, count: 1 });
    }

    // Durable cooldown via rate_limits table (best-effort)
    try {
      const serviceClient = getServiceClient();
      const { data: rl } = await serviceClient
        .from("rate_limits")
        .select("last_request_at")
        .eq("user_id", user.id)
        .maybeSingle();
      if (rl?.last_request_at) {
        const dbDiff = now - new Date(rl.last_request_at).getTime();
        if (dbDiff < REQUEST_COOLDOWN_MS) {
          const retryAfterSec = Math.ceil((REQUEST_COOLDOWN_MS - dbDiff) / 1000);
          return new Response(
            JSON.stringify({ error: "Rate limit exceeded", code: "AI_RATE_LIMITED", message: "Rate limit exceeded. Please wait a moment before sending another message.", retry_after_seconds: retryAfterSec }),
            { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
      }
      await serviceClient.from("rate_limits").upsert({ user_id: user.id, last_request_at: new Date().toISOString() });
    } catch (_dbError) { /* memory limiter already guarded */ }

    // Payload
    let payload: any;
    try {
      payload = await req.json();
    } catch (_e) {
      return new Response(
        JSON.stringify({ error: "Invalid JSON request payload", code: "AI_INVALID_REQUEST", message: "Invalid JSON request payload" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const rawPersona = payload?.persona_id ? String(payload.persona_id).toLowerCase().trim() : "";
    let canonicalPersona: "ventaxis" | "hexagon";
    if (rawPersona === "ventaxis") {
      canonicalPersona = "ventaxis";
    } else {
      // Tolerant fallback: unknown/missing personas resolve to hexagon
      canonicalPersona = "hexagon";
    }
    const systemPrompt = canonicalPersona === "ventaxis" ? VENTAXIS_SYSTEM_PROMPT : HEXAGON_SYSTEM_PROMPT;

    let rawMessages: any[] = Array.isArray(payload?.messages)
      ? payload.messages
      : payload?.prompt
        ? [{ role: "user", text: payload.prompt }]
        : [];
    if (rawMessages.length === 0) rawMessages.push({ role: "user", text: "Hello" });

    const contextBudget = rawMessages.slice(-10);
    const hasImage = contextBudget.some((m: any) => {
      const img = m.image ?? m.image_base64 ?? m.data;
      return img && typeof img === "string" && img.length > 20;
    });

    const contents = contextBudget
      .map((msg: any) => {
        const textVal = String(msg.text ?? msg.content ?? "").trim().slice(0, 4000);
        const parts: any[] = [];
        if (textVal) parts.push({ text: textVal });
        const imgData = msg.image ?? msg.image_base64 ?? msg.data;
        if (imgData && typeof imgData === "string" && imgData.length > 20) {
          const mime = msg.mime_type ?? msg.mimeType ?? "image/jpeg";
          parts.push({ image_url: { url: `data:${mime};base64,${imgData}` } });
        }
        if (parts.length === 0) return null;
        return {
          role: msg.role === "assistant" || msg.role === "model" ? "model" : "user",
          parts: parts,
        };
      })
      .filter((c: any) => c !== null);

    if (contents.length === 0) {
      contents.push({ role: "user", parts: [{ text: "Hello" }] });
    }

    // Web search (Tavily) for search-like user questions; skipped for image turns
    let webContext: string | null = null;
    const lastUserText = latestUserText(contextBudget);
    if (TAVILY_API_KEY && !hasImage && lastUserText.length > 2 && SEARCH_TRIGGER_RE.test(lastUserText)) {
      webContext = await tavilySearch(lastUserText);
    }

    const chain = hasImage ? MODEL_CHAINS.vision : MODEL_CHAINS[canonicalPersona];
    const groqMessages = toGroqMessages(systemPrompt, contents, webContext);
    const visionFallbackNotice = hasImage
      ? "\n\n(Analysis of the attached image is temporarily unavailable; answering the text part.)"
      : "";

    // Invoke Groq with model fallback chain
    let reply: string | null = null;
    let usedModel = "";
    let lastUpstreamStatus = 0;
    for (const spec of chain) {
      try {
        const reqBody: any = {
          model: spec.model,
          messages: groqMessages,
          max_completion_tokens: spec.max_completion_tokens,
          temperature: spec.temperature,
          top_p: spec.top_p,
          stop: null,
        };
        if (spec.reasoning_effort) reqBody.reasoning_effort = spec.reasoning_effort;
        if (hasImage && spec.model.startsWith("meta-llama/")) {
          reqBody.messages = groqMessages;
        }
        const res = await fetch(GROQ_URL, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${GROQ_API_KEY}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(reqBody),
          signal: AbortSignal.timeout(20000),
        });
        if (!res.ok) {
          lastUpstreamStatus = res.status;
          continue; // try next model in chain
        }
        const data = await res.json();
        let candidate = data?.choices?.[0]?.message?.content;
        if (candidate && typeof candidate === "string" && candidate.trim().length > 0) {
          reply = candidate.trim() + (hasImage && spec.model.startsWith("qwen/") ? visionFallbackNotice : "");
          usedModel = spec.model;
          break;
        }
      } catch (_e) {
        continue;
      }
    }

    if (!reply) {
      const code = lastUpstreamStatus === 429 ? "AI_RATE_LIMITED" : "AI_UPSTREAM_ERROR";
      return new Response(
        JSON.stringify({
          error: `Upstream model error (${lastUpstreamStatus})`,
          code: code,
          message: `Upstream model error (${lastUpstreamStatus}) for persona ${canonicalPersona}`,
        }),
        { status: lastUpstreamStatus === 429 ? 429 : 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({ reply: reply, persona_id: canonicalPersona, model: usedModel }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Internal server error", code: "AI_SERVER_ERROR", message: "An internal server error occurred while processing the request." }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
