// Unit tests for the verify-telegram edge function.
// Run: deno test -A supabase/functions/_tests/

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("SUPABASE_URL", "https://stub.supabase.co");
Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "stub-service-key");
Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");

const { handler } = await import("../verify-telegram/index.ts");

async function sha256Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.test("successful verification sends the bot link confirmation to the linked chat", async () => {
  const calls: { endpoint: string; body: any }[] = [];
  const originalFetch = globalThis.fetch;
  const json = (obj: unknown, status = 200) =>
    new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

  const codeHash = await sha256Hex("123456");
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("api.telegram.org")) {
      calls.push({ endpoint: url, body: init?.body ? JSON.parse(String(init.body)) : {} });
      return Promise.resolve(json({ ok: true }));
    }
    if (url.includes("/auth/v1/user")) return Promise.resolve(json({ id: "acc-x", aud: "authenticated" }));
    if (url.includes("/rest/v1/telegram_challenges")) {
      return Promise.resolve(json({
        challenge_id: "ch1",
        account_id: "acc-x",
        is_used: false,
        expires_at: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
        attempts: 0,
        max_attempts: 5,
        telegram_user_id: 777,
        telegram_username: "u",
        code_hash: codeHash,
      }));
    }
    if (url.includes("/rest/v1/rpc/verify_telegram_challenge_atomic")) {
      return Promise.resolve(json({ success: true, telegram_user_id: 777, telegram_username: "u" }));
    }
    return Promise.resolve(json({ message: "stub" }, 500));
  }) as typeof fetch;

  try {
    const res = await handler(new Request("https://project.functions.supabase.co/verify-telegram", {
      method: "POST",
      headers: { "content-type": "application/json", Authorization: "Bearer user-jwt" },
      body: JSON.stringify({ challenge_id: "ch1", code: "123456" }),
    }));
    assertEquals(res.status, 200);
    const jsonBody = await res.json();
    assertEquals(jsonBody.verified, true);

    const confirmation = calls.find((c) => c.endpoint.includes("sendMessage"));
    assertEquals(!!confirmation, true, "the bot must confirm the link in Telegram");
    assertEquals(confirmation?.body.chat_id, 777, "confirmation goes to the linked telegram chat");
    assertEquals(String(confirmation?.body.text ?? "").includes("привязан"), true);
    assertEquals(String(confirmation?.body.text ?? "").includes("@u"), true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("wrong code is rejected without any bot confirmation", async () => {
  const calls: { endpoint: string; body: any }[] = [];
  const originalFetch = globalThis.fetch;
  const json = (obj: unknown, status = 200) =>
    new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

  const codeHash = await sha256Hex("123456");
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("api.telegram.org")) {
      calls.push({ endpoint: url, body: init?.body ? JSON.parse(String(init.body)) : {} });
      return Promise.resolve(json({ ok: true }));
    }
    if (url.includes("/auth/v1/user")) return Promise.resolve(json({ id: "acc-x" }));
    if (url.includes("/rest/v1/telegram_challenges")) {
      return Promise.resolve(json({
        challenge_id: "ch2", account_id: "acc-x", is_used: false,
        expires_at: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
        attempts: 0, max_attempts: 5, telegram_user_id: 777, code_hash: codeHash,
      }));
    }
    if (url.includes("/rest/v1/rpc/verify_telegram_challenge_atomic")) {
      return Promise.resolve(json({ success: false, code: "INVALID_CODE", message: "Wrong code", remaining_attempts: 4 }));
    }
    return Promise.resolve(json({ message: "stub" }, 500));
  }) as typeof fetch;

  try {
    const res = await handler(new Request("https://project.functions.supabase.co/verify-telegram", {
      method: "POST",
      headers: { "content-type": "application/json", Authorization: "Bearer user-jwt" },
      body: JSON.stringify({ challenge_id: "ch2", code: "999999" }),
    }));
    assertEquals(res.status, 400);
    assertEquals(calls.some((c) => c.endpoint.includes("sendMessage")), false, "no confirmation for a failed code");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
