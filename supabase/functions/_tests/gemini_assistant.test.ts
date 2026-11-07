// Unit tests for the gemini-assistant edge function.
// Run: deno test -A supabase/functions/_tests/
// The Groq/Supabase network is stubbed via globalThis.fetch — no real calls are made.

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("GROQ_API_KEY", "test-groq-key");
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

Deno.test("missing persona_id is rejected (no silent fallback)", async () => {
  installFetchStub();
  try {
    const res = await handler(aiRequest(undefined, "tok-missing-persona"));
    assertEquals(res.status, 400);
    const json = await res.json();
    assertEquals(json.code, "UNKNOWN_PERSONA");
    assertEquals(groqCalls.length, 0);
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
