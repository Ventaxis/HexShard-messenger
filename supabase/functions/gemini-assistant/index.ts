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
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }
  const token = authHeader.substring(7).trim();
  if (!token) {
    return null;
  }

  try {
    const userClient = getUserClient(authHeader);
    const { data: { user }, error } = await userClient.auth.getUser();
    if (!error && user) {
      return user;
    }
  } catch (_e) {
    // fallback
  }

  try {
    const serviceClient = getServiceClient();
    const { data: { user }, error } = await serviceClient.auth.getUser(token);
    if (!error && user) {
      return user;
    }
  } catch (_e) {
    return null;
  }

  return null;
}

const memoryRateLimit = new Map<string, number>();

// Server-authoritative allowed AI models
const ALLOWED_AI_MODELS = new Set([
  "gemini-3.5-flash",
  "gemini-2.5-flash",
  "gemini-flash-latest",
  "gemini-3.1-flash-lite-preview",
  "gemini-3.6-flash",
  "gemini-3.5-flash-lite"
]);

const VENTAXIS_SYSTEM_PROMPT = `You are Ventaxis AI, the advanced analytical AI persona built directly inside HexShard Messenger.

Identity & Ecosystem:
You are Ventaxis AI. Do not call yourself Gemini or an external model. You operate natively within HexShard Messenger — a privacy-first, decentralized messenger styled with a sleek Spotify-like dark green UI (#121212 and #1DB954).
HexShard features you deeply understand:
- Cryptographic Identity (+999): Every user can claim a decentralized, anonymous +999 8-digit virtual identity (e.g., +999 1234 5678) without exposing physical SIM cards or phone numbers.
- True End-to-End Encryption (E2EE): Keys (Ed25519/X25519) stay in Android Keystore on-device; server only relays encrypted packets; local cache is encrypted with SQLCipher 256-bit AES.
- Media & Customization: HexShard supports voice messages, media attachments, photos, videos, custom profile backgrounds (solid, gradient, photo/video), and Saved Messages ("Избранное") with personal cloud sync.
- Visual Inspection: You can view, analyze, describe, and inspect photos and images sent to you by the user! When the user attaches an image, thoroughly analyze what is shown, provide helpful insights, code review, translations, or explanations.

Personality:
Calm, intellectual, deeply analytical, structured, mathematically and technically precise, privacy-conscious.
Behavior:
- Give comprehensive, well-structured, intelligent answers. Never use generic or canned templates.
- Respond in the language of the user (Russian or English).
- When analyzing code or architecture, provide practical examples and clear explanations.`;

const HEXAGON_SYSTEM_PROMPT = `You are Hexagon AI, the ultra-fast, pragmatic built-in AI persona inside HexShard Messenger.

Identity & Ecosystem:
You are Hexagon AI. Do not call yourself Gemini or an external model. You operate natively within HexShard Messenger — a privacy-first, decentralized messenger styled with a sleek Spotify-like dark green UI (#121212 and #1DB954).
HexShard features you deeply understand:
- Cryptographic Identity (+999): Anonymous +999 8-digit virtual phone numbers without physical SIM cards.
- True E2EE: On-device encryption with Android Keystore, SQLCipher local cache, zero server surveillance.
- Saved Messages ("Избранное"): Fast cloud sync for personal notes, voice messages, files.
- Visual Inspection: You can inspect and understand photos and images sent directly in this chat! Describe, critique, solve, or explain images immediately.

Personality:
High-speed, sharp, concise, pragmatic, actionable, direct.
Behavior:
- Get straight to the point. No fluff, no canned template responses.
- Respond in the language of the user (Russian or English).`;

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // 1. Strict Authenticated User Check (fail-closed)
    const user = await getAuthenticatedUser(req);
    if (!user) {
      return new Response(
        JSON.stringify({
          error: "User authentication required",
          code: "AI_UNAUTHORIZED",
          message: "User authentication required"
        }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Durable Rate Limiting (1.5s interval per account)
    const now = Date.now();
    const lastMemoryReq = memoryRateLimit.get(user.id) ?? 0;
    const memoryDiff = now - lastMemoryReq;
    if (memoryDiff < 1500) {
      const retryAfterSec = Math.ceil((1500 - memoryDiff) / 1000);
      return new Response(
        JSON.stringify({
          error: "Rate limit exceeded",
          code: "AI_RATE_LIMITED",
          message: "Rate limit exceeded. Please wait a moment before sending another message.",
          retry_after_seconds: retryAfterSec
        }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }
    memoryRateLimit.set(user.id, now);

    try {
      const serviceClient = getServiceClient();
      const { data: rl } = await serviceClient
        .from("rate_limits")
        .select("last_request_at")
        .eq("user_id", user.id)
        .maybeSingle();

      if (rl?.last_request_at) {
        const lastDbTime = new Date(rl.last_request_at).getTime();
        const dbDiff = now - lastDbTime;
        if (dbDiff < 1500) {
          const retryAfterSec = Math.ceil((1500 - dbDiff) / 1000);
          return new Response(
            JSON.stringify({
              error: "Rate limit exceeded",
              code: "AI_RATE_LIMITED",
              message: "Rate limit exceeded. Please wait a moment before sending another message.",
              retry_after_seconds: retryAfterSec
            }),
            { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
      }

      await serviceClient
        .from("rate_limits")
        .upsert({ user_id: user.id, last_request_at: new Date().toISOString() });
    } catch (_dbError) {
      // Memory rate limiter has already guarded this cycle
    }

    // 3. Payload validation
    let payload: any;
    try {
      payload = await req.json();
    } catch (_e) {
      return new Response(
        JSON.stringify({
          error: "Invalid JSON request payload",
          code: "AI_INVALID_REQUEST",
          message: "Invalid JSON request payload"
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const rawPersona = payload?.persona_id ? String(payload.persona_id).toLowerCase().trim() : "";
    if (!rawPersona) {
      return new Response(
        JSON.stringify({
          error: "persona_id is required",
          code: "AI_INVALID_REQUEST",
          message: "persona_id is required. Allowed personas: ventaxis, hexagon"
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    let canonicalPersona: "ventaxis" | "hexagon";
    let modelId: string;
    let systemPrompt: string;
    let temperature: number;

    if (rawPersona === "ventaxis") {
      canonicalPersona = "ventaxis";
      modelId = "gemini-3.6-flash";
      systemPrompt = VENTAXIS_SYSTEM_PROMPT;
      temperature = 0.7;
    } else if (rawPersona === "hexagon") {
      canonicalPersona = "hexagon";
      modelId = "gemini-3.5-flash-lite";
      systemPrompt = HEXAGON_SYSTEM_PROMPT;
      temperature = 0.3;
    } else {
      return new Response(
        JSON.stringify({
          error: `Unknown AI persona: ${rawPersona}`,
          code: "AI_INVALID_REQUEST",
          message: `Unknown AI persona: ${rawPersona}. Allowed personas: ventaxis, hexagon`
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!ALLOWED_AI_MODELS.has(modelId)) {
      return new Response(
        JSON.stringify({
          error: `Model ${modelId} is not authorized`,
          code: "AI_MODEL_UNAVAILABLE",
          message: `Model ${modelId} is not authorized`
        }),
        { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const rawMessages: any[] = Array.isArray(payload?.messages)
      ? payload.messages
      : payload?.prompt
        ? [{ role: "user", text: payload.prompt }]
        : [];

    if (rawMessages.length === 0) {
      return new Response(
        JSON.stringify({
          error: "At least one message is required",
          code: "AI_INVALID_REQUEST",
          message: "At least one message is required"
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Context budget: last 10 messages
    // Canonical text field is "text", fallback to "content", with optional image
    const contextBudget = rawMessages.slice(-10);
    const contents = contextBudget
      .map((msg: any) => {
        const textVal = String(msg.text ?? msg.content ?? "").trim().slice(0, 4000);
        const parts: any[] = [];
        if (textVal) {
          parts.push({ text: textVal });
        }
        const imgData = msg.image ?? msg.image_base64 ?? msg.data;
        if (imgData && typeof imgData === "string" && imgData.length > 20) {
          parts.push({
            inline_data: {
              mime_type: msg.mime_type ?? msg.mimeType ?? "image/jpeg",
              data: imgData
            }
          });
        }
        if (parts.length === 0) return null;
        return {
          role: msg.role === "assistant" || msg.role === "model" ? "model" : "user",
          parts: parts
        };
      })
      .filter((c) => c !== null);

    if (contents.length === 0) {
      return new Response(
        JSON.stringify({
          error: "No valid message text found in payload",
          code: "AI_INVALID_REQUEST",
          message: "No valid message text found in payload"
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const apiKey = Deno.env.get("GEMINI_API_KEY");
    if (!apiKey) {
      return new Response(
        JSON.stringify({
          error: "AI service configuration is missing on the server",
          code: "AI_UNAVAILABLE",
          message: "AI service configuration is missing on the server."
        }),
        { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 4. Exact model invocation without fallback or rotation
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelId}:generateContent?key=${apiKey}`;
    const reqBody = {
      contents: contents,
      systemInstruction: {
        parts: [{ text: systemPrompt }]
      },
      generationConfig: {
        maxOutputTokens: 1024,
        temperature: temperature
      }
    };

    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(reqBody),
      signal: AbortSignal.timeout(8000)
    });

    if (!res.ok) {
      const errStatus = res.status;
      let code = "AI_UPSTREAM_ERROR";
      if (errStatus === 429) {
        code = "AI_RATE_LIMITED";
      } else if (errStatus === 404 || errStatus === 400) {
        code = "AI_MODEL_UNAVAILABLE";
      } else if (errStatus >= 500) {
        code = "AI_UNAVAILABLE";
      }

      return new Response(
        JSON.stringify({
          error: `Upstream model error (${errStatus})`,
          code: code,
          message: `Upstream model error (${errStatus}) for persona ${canonicalPersona}`
        }),
        { status: errStatus === 429 ? 429 : 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const data = await res.json();
    const candidate = data?.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!candidate || typeof candidate !== "string" || candidate.trim().length === 0) {
      return new Response(
        JSON.stringify({
          error: "No content returned from AI model",
          code: "AI_UNAVAILABLE",
          message: "No content returned from AI model"
        }),
        { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Canonical response contract: { reply, persona_id, model }
    return new Response(
      JSON.stringify({
        reply: candidate.trim(),
        persona_id: canonicalPersona,
        model: modelId
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({
        error: "Internal server error",
        code: "AI_SERVER_ERROR",
        message: "An internal server error occurred while processing the request."
      }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
