// Unit tests for the telegram-webhook edge function.
// Run: deno test -A supabase/functions/_tests/

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");

const { handler } = await import("../telegram-webhook/index.ts");

const WEBHOOK_SECRET = "hexshard-test-webhook-secret-2026";

function clearSecretEnv() {
  for (const key of ["TELEGRAM_WEBHOOK_SECRET", "WEBHOOK_SECRET", "APP_ENV", "DENO_ENV"]) {
    try {
      Deno.env.delete(key);
    } catch (_e) { /* unset already */ }
  }
}

function postWithToken(token: string | undefined, body: unknown): Request {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token !== undefined) headers["x-telegram-bot-api-secret-token"] = token;
  return new Request("https://project.functions.supabase.co/telegram-webhook", {
    method: "POST",
    headers,
    body: JSON.stringify(body)
  });
}

Deno.test("GET health check returns ok", async () => {
  const res = await handler(new Request("https://project.functions.supabase.co/telegram-webhook", { method: "GET" }));
  assertEquals(res.status, 200);
  const json = await res.json();
  assertEquals(json.status, "ok");
  assertEquals(json.service, "hexshard-telegram-webhook");
});

Deno.test("POST with no configured secret fails closed with 401", async () => {
  clearSecretEnv();
  const res = await handler(postWithToken("anything", { update_id: 1 }));
  assertEquals(res.status, 401);
});

Deno.test("wrong secret token is rejected regardless of length (digest compare leaks no length)", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  try {
    // Shorter than the secret...
    const short = await handler(postWithToken("short", { update_id: 1 }));
    // ...and same length but different content — both must yield the same 401.
    const sameLength = await handler(postWithToken("x".repeat(WEBHOOK_SECRET.length), { update_id: 2 }));
    assertEquals(short.status, 401);
    assertEquals(sameLength.status, 401);
    assertEquals(await short.text(), await sameLength.text());
  } finally {
    clearSecretEnv();
  }
});

Deno.test("correct secret passes authentication and reaches the bot-token branch", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  try {
    for (const key of ["TELEGRAM_BOT_TOKEN", "BOT_TOKEN", "HEXSHARD_TELEGRAM_BOT_TOKEN"]) {
      try {
        Deno.env.delete(key);
      } catch (_e) { /* unset already */ }
    }
    const res = await handler(postWithToken(WEBHOOK_SECRET, { update_id: 3 }));
    // Auth passed (not 401); with no bot token configured the handler stops at the
    // configuration branch — this is exactly where it must stop in the test env.
    assertEquals(res.status, 500);
    const json = await res.json();
    assertEquals(json.error, "TELEGRAM_BOT_TOKEN not configured");
  } finally {
    clearSecretEnv();
  }
});
