// Unit tests for the gemini-assistant edge function.
// Run: deno test -A supabase/functions/_tests/
// The Groq/Supabase network is stubbed via globalThis.fetch — no real calls are made.

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("GROQ_API_KEY", "gsk_test_key_for_unit_tests");
Deno.env.set("TAVILY_API_KEY", "stub-tavily-key");
Deno.env.set("SUPABASE_URL", "https://stub.supabase.co");
Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "stub-service-key");

const { handler } = await import("../gemini-assistant/index.ts");

const groqCalls: string[] = [];
const groqBodies: Record<string, unknown>[] = [];
const originalFetch = globalThis.fetch;

function installFetchStub(opts: { groqBody?: unknown; groqStatus?: number } = {}) {
  groqCalls.length = 0;
  groqBodies.length = 0;
  const stub = (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("api.tavily.com")) {
      return Promise.resolve(
        new Response(
          JSON.stringify({ results: [{ title: "Quantum computer", content: "QC uses qubits", url: "https://example.com/qc" }] }),
          { status: 200, headers: { "content-type": "application/json" } }
        )
      );
    }
    if (url.includes("api.groq.com")) {
      groqCalls.push(url);
      try {
        groqBodies.push(JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>);
      } catch (_e) {
        groqBodies.push({});
      }
      const body = JSON.stringify(
        opts.groqBody ?? { choices: [{ message: { content: "Привет!" } }], model: "stub-model" }
      );
      return Promise.resolve(
        new Response(body, { status: opts.groqStatus ?? 200, headers: { "content-type": "application/json" } })
      );
    }
    if (url.includes("/auth/v1/user")) {
      const headers = init?.headers;
      let bearer = "";
      if (headers instanceof Headers) {
        bearer = headers.get("authorization") ?? "";
      } else if (headers && typeof headers === "object") {
        bearer = (headers as Record<string, string>)["Authorization"] ??
          (headers as Record<string, string>)["authorization"] ?? "";
      }
      const uid = bearer.replace(/^Bearer\s+/i, "").trim() || "anon";
      return Promise.resolve(
        new Response(JSON.stringify({ id: `user-${uid}`, aud: "authenticated" }), {
          status: 200,
          headers: { "content-type": "application/json" }
        })
      );
    }
    // Supabase REST (durable rate_limits): a 500 makes the best-effort path bail out safely.
    return Promise.resolve(new Response("stub: service unavailable", { status: 500 }));
  };
  globalThis.fetch = stub as typeof fetch;
}

function restoreFetch() {
  globalThis.fetch = originalFetch;
}

function aiRequest(personaId: string | undefined, token: string): Request {
  const payload: Record<string, unknown> = { messages: [{ role: "user", text: "Привет" }] };
  if (personaId !== undefined) payload.persona_id = personaId;
  return new Request("https://project.functions.supabase.co/gemini-assistant", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify(payload)
  });
}

Deno.test("unknown persona_id is rejected with UNKNOWN_PERSONA and never reaches Groq", async () => {
  installFetchStub();
  try {
    const res = await handler(aiRequest("hexgarbage", "tok-unknown-persona"));
    assertEquals(res.status, 400);
    const json = await res.json();
    assertEquals(json.code, "UNKNOWN_PERSONA");
    assertEquals(groqCalls.length, 0, "Groq must not be called for an unknown persona");
  } finally {
    restoreFetch();
  }
});

Deno.test("valid ventaxis persona returns the Groq reply with the persona echoed", async () => {
  installFetchStub({ groqBody: { choices: [{ message: { content: "Привет, я Вентаксис!" } }], model: "openai/gpt-oss-120b" } });
  try {
    const res = await handler(aiRequest("ventaxis", "tok-happy"));
    assertEquals(res.status, 200);
    const json = await res.json();
    assertEquals(json.reply, "Привет, я Вентаксис!");
    assertEquals(json.persona_id, "ventaxis");
    assertEquals(groqCalls.length, 1, "Exactly one Groq request expected");
  } finally {
    restoreFetch();
  }
});

Deno.test("requests without a Bearer token are rejected as unauthorized", async () => {
  installFetchStub();
  try {
    const req = new Request("https://project.functions.supabase.co/gemini-assistant", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ persona_id: "ventaxis", messages: [{ role: "user", text: "hi" }] })
    });
    const res = await handler(req);
    assertEquals(res.status, 401);
    const json = await res.json();
    assertEquals(json.code, "AI_UNAUTHORIZED");
    assertEquals(groqCalls.length, 0);
  } finally {
    restoreFetch();
  }
});

Deno.test("web search context is delivered as delimited untrusted user message, never a system role", async () => {
  installFetchStub({ groqBody: { choices: [{ message: { content: "Ответ с вебом!" } }], model: "stub-model" } });
  try {
    const req = new Request("https://project.functions.supabase.co/gemini-assistant", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer tok-web" },
      body: JSON.stringify({
        persona_id: "hexagon",
        messages: [{ role: "user", text: "Что такое квантовый компьютер?" }]
      })
    });
    const res = await handler(req);
    assertEquals(res.status, 200);
    assertEquals(groqCalls.length, 1, "Tavily search and Groq call both happen");

    const messages = groqBodies[0].messages as { role: string; content: unknown }[];
    assertEquals(
      messages.filter((m) => m.role === "system").length, 1,
      "exactly one system message (the persona prompt) is allowed"
    );
    const webMsg = messages.find((m) => m.role === "user" && JSON.stringify(m.content).includes("<web_context>"));
    assertEquals(webMsg !== undefined, true, "web context must be a user message wrapped in <web_context> delimiters");
    assertEquals(JSON.stringify(webMsg?.content).includes("untrusted external data"), true,
      "the untrusted-data warning must travel with the web context");
  } finally {
    restoreFetch();
  }
});

Deno.test("malformed Groq key (missing gsk_ prefix) is rejected with a loud config error", async () => {
  installFetchStub();
  const realKey = Deno.env.get("GROQ_API_KEY");
  Deno.env.set("GROQ_API_KEY", "4d925f3a00b47dd64fef"); // 64-hex style misconfiguration
  try {
    const res = await handler(new Request("https://project.functions.supabase.co/gemini-assistant", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer tok-bad-key" },
      body: JSON.stringify({ persona_id: "hexagon", messages: [{ role: "user", text: "hi" }] })
    }));
    assertEquals(res.status, 503);
    const json = await res.json();
    assertEquals(json.code, "AI_CONFIG_ERROR");
    assertEquals(json.error.includes("gsk_"), true, "the error must name the gsk_ format requirement");
    assertEquals(groqCalls.length, 0, "no Groq call is made with a malformed key");
  } finally {
    if (realKey !== undefined) Deno.env.set("GROQ_API_KEY", realKey);
    restoreFetch();
  }
});

Deno.test("missing persona_id falls back to the documented default (hexagon) for old clients", async () => {
  installFetchStub({ groqBody: { choices: [{ message: { content: "Ответ по умолчанию" } }], model: "qwen/qwen3.8-27b" } });
  try {
    const req = new Request("https://project.functions.supabase.co/gemini-assistant", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer tok-no-persona" },
      body: JSON.stringify({ messages: [{ role: "user", text: "hi" }] })
    });
    const res = await handler(req);
    assertEquals(res.status, 200);
    const json = await res.json();
    assertEquals(json.persona_id, "hexagon");
    assertEquals(groqCalls.length, 1);
  } finally {
    restoreFetch();
  }
});

Deno.test("vision failure falls back to text-only answer instead of 502", async () => {
  // First Groq call (vision chain, image attached) fails with upstream 400;
  // the text-only retry (image stripped) must succeed and return the reply.
  let groqAttempt = 0;
  installFetchStub();
  try {
    globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      if (url.includes("api.tavily.com")) {
        return Promise.resolve(new Response(JSON.stringify({ results: [] }), { status: 200 }));
      }
      if (url.includes("api.groq.com")) {
        groqAttempt++;
        groqCalls.push(url);
        try {
          groqBodies.push(JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>);
        } catch (_e) { groqBodies.push({}); }
        // All three vision-chain models fail (e.g. deprecated upstream); only the
        // text-only retry (attempt 4) succeeds.
        if (groqAttempt <= 3) {
          return Promise.resolve(new Response(JSON.stringify({ error: { message: "model does not support images" } }), { status: 400 }));
        }
        return Promise.resolve(new Response(JSON.stringify({ choices: [{ message: { content: "Это квадрат." } }], model: "qwen" }), { status: 200 }));
      }
      if (url.includes("/auth/v1/user")) {
        return Promise.resolve(new Response(JSON.stringify({ id: "user-tok-vision", aud: "authenticated" }), { status: 200 }));
      }
      return Promise.resolve(new Response("stub", { status: 500 }));
    }) as typeof fetch;
    try {
      const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
      const res = await handler(new Request("https://project.functions.supabase.co/gemini-assistant", {
        method: "POST",
        headers: { "content-type": "application/json", authorization: "Bearer tok-vision" },
        body: JSON.stringify({ persona_id: "hexagon", messages: [{ role: "user", text: "что на картинке", image: PNG, mime_type: "image/png" }] })
      }));
      assertEquals(res.status, 200, "text-only fallback must rescue the image path");
      const json = await res.json();
      assertEquals(json.reply.includes("Это квадрат."), true);
      // The user must be told the image could not be analyzed
      assertEquals(json.reply.includes("temporarily unavailable"), true, "fallback notice is required");
      assertEquals(groqAttempt >= 2, true, "vision attempt + text-only retry");
      // Somewhere in the chain there must be a text-only retry: no image payload,
      // and content flattened to plain strings (text-only Groq models reject
      // part-array content)
      const retryBody = groqBodies.find((b) => !JSON.stringify(b).includes("image_url"));
      assertEquals(retryBody !== undefined, true, "a text-only retry must happen");
      assertEquals(
        ((retryBody as Record<string, unknown>).messages as { content: unknown }[]).every((m) => typeof m.content === "string"),
        true,
        "retry content must be flattened to strings"
      );
    } finally {
      restoreFetch();
    }
  } finally {
    restoreFetch();
  }
});

Deno.test("error responses carry meta (region, model_attempted) for diagnostics", async () => {
  installFetchStub();
  try {
    const req = new Request("https://project.functions.supabase.co/gemini-assistant", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer tok-meta" },
      body: JSON.stringify({ persona_id: "hexgarbage", messages: [{ role: "user", text: "hi" }] })
    });
    const res = await handler(req);
    assertEquals(res.status, 400);
    const json = await res.json();
    assertEquals(json.code, "UNKNOWN_PERSONA");
    assertEquals(json.meta !== undefined, true, "diagnostic meta block is required");
    assertEquals(typeof json.meta.region, "string");
  } finally {
    restoreFetch();
  }
});
