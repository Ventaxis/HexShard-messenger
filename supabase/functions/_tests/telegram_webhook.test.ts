// Unit tests for the telegram-webhook edge function.
// Run: deno test -A supabase/functions/_tests/

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("SUPABASE_URL", "https://stub.supabase.co");
Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "stub-service-key");

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

Deno.test("challenge minting via /start <account uuid> is rate-limited per telegram chat", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 987001;
    const inserts: string[] = [];
    const telegramTexts: string[] = [];
    const originalFetch = globalThis.fetch;

    const json = (obj: unknown, status = 200) =>
      new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

    // Dynamic supabase stub: route by table, remember mint inserts.
    globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const method = (init?.method ?? "GET").toUpperCase();

      if (url.includes("api.telegram.org") && url.includes("sendMessage")) {
        const body = JSON.parse(String(init?.body ?? "{}")) as { text?: string };
        telegramTexts.push(body.text ?? "");
        return Promise.resolve(json({ ok: true }));
      }
      if (url.includes("/rest/v1/telegram_processed_updates")) return Promise.resolve(json({}, 201));
      if (url.includes("/rest/v1/telegram_challenges")) {
        if (method === "POST") {
          const body = JSON.parse(String(init?.body ?? "{}")) as { account_id?: string };
          inserts.push(body.account_id ?? "?");
          return Promise.resolve(json(body, 201));
        }
        if (method === "PATCH") return Promise.resolve(new Response(null, { status: 204 }));
        // GET lookups (by challenge_id / by account): nothing pending, force the mint path.
        return Promise.resolve(json(null));
      }
      if (url.includes("/rest/v1/profiles")) {
        // Any UUID in /start resolves to a profile — the abuse the rate limit must contain.
        const id = decodeURIComponent(url.split("id=eq.")[1] ?? "");
        return Promise.resolve(json({ id }));
      }
      return Promise.resolve(json({ message: "stub" }, 500));
    }) as typeof fetch;

    try {
      // Three distinct accounts mint fine...
      for (let i = 0; i < 3; i++) {
        const uuid = `11111111-2222-3333-4444-${(555555555555 + i).toString().padStart(12, "0")}`;
        const update = {
          update_id: 1000 + i,
          message: { message_id: 1000 + i, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: `/start ${uuid}` }
        };
        const res = await handler(postWithToken(WEBHOOK_SECRET, update));
        assertEquals(res.status, 200);
      }
      assertEquals(inserts.length, 3, "each distinct account mints one challenge");

      // ...the fourth within the window is refused without touching the database.
      const fourthUuid = "11111111-2222-3333-4444-555555555599";
      const update = {
        update_id: 1003,
        message: { message_id: 1003, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: `/start ${fourthUuid}` }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(inserts.length, 3, "rate-limited mint must not insert a challenge");
      assertEquals(telegramTexts.some((t) => t.includes("Подождите") || t.includes("⏳")), true,
        "the bot must tell the user to wait instead of minting");
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/recover mints a one-time ticket bound to the verified telegram link", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 987654321;
    const inserts: Record<string, unknown>[] = [];
    const invalidations = 0;
    const telegramTexts: string[] = [];
    const originalFetch = globalThis.fetch;
    const json = (obj: unknown, status = 200) =>
      new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

    globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      if (url.includes("api.telegram.org") && url.includes("sendMessage")) {
        const body = JSON.parse(String(init?.body ?? "{}")) as { text?: string };
        telegramTexts.push(body.text ?? "");
        return Promise.resolve(json({ ok: true }));
      }
      if (url.includes("/rest/v1/telegram_processed_updates")) return Promise.resolve(json({}, 201));
      if (url.includes("/rest/v1/telegram_links")) return Promise.resolve(json({ account_id: "acc-recover-me", verified: true }));
      if (url.includes("/rest/v1/account_recovery_tickets")) {
        if (method === "POST") {
          const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          inserts.push(body);
          return Promise.resolve(json(body, 201));
        }
        if (method === "PATCH") return Promise.resolve(new Response(null, { status: 204 }));
        return Promise.resolve(json([]));
      }
      return Promise.resolve(json({ message: "stub" }, 500));
    }) as typeof fetch;

    try {
      const update = {
        update_id: 5000,
        message: { message_id: 5000, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/recover" }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(inserts.length, 1, "exactly one ticket minted");
      const ticket = inserts[0] as { account_id?: string; telegram_user_id?: number; ticket_hash?: string; ticket_id?: string };
      assertEquals(ticket.account_id, "acc-recover-me", "ticket must be bound to the verified linked account");
      assertEquals(ticket.telegram_user_id, CHAT_ID);
      assertEquals(typeof ticket.ticket_hash, "string");
      assertEquals((ticket.ticket_hash as string).length, 64, "ticket must be stored hashed (SHA-256)");
      assertEquals((ticket.ticket_id as string).length >= 48, true, "ticket id must be a long random bearer secret");
      assertEquals(telegramTexts.some((t) => t.includes(ticket.ticket_id as string)), true, "bot sends the ticket to its owner");
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/recover without a verified link explains and mints nothing", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 111000111;
    const telegramTexts: string[] = [];
    const originalFetch = globalThis.fetch;
    const json = (obj: unknown, status = 200) =>
      new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
    globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      if (url.includes("api.telegram.org") && url.includes("sendMessage")) {
        const body = JSON.parse(String(init?.body ?? "{}")) as { text?: string };
        telegramTexts.push(body.text ?? "");
        return Promise.resolve(json({ ok: true }));
      }
      if (url.includes("/rest/v1/telegram_processed_updates")) return Promise.resolve(json({}, 201));
      if (url.includes("/rest/v1/telegram_links")) return Promise.resolve(json(null));
      return Promise.resolve(json({ message: "stub" }, 500));
    }) as typeof fetch;
    try {
      const update = {
        update_id: 5001,
        message: { message_id: 5001, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/recover" }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(telegramTexts.some((t) => t.includes("привязан") || t.includes("No verified")), true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

// ---------------------------------------------------------------------------
// Owner round 2026-11-15: «привязал я или нет, почему коды приходят постоянно?»
// The bot must know about existing verified links: no endless code minting,
// visible status, buttons and an unlink flow.

interface CapturedTgCall { endpoint: string; body: any }

function tgTexts(calls: CapturedTgCall[]): string[] {
  return calls.filter((c) => c.endpoint.includes("sendMessage")).map((c) => String(c.body.text ?? ""));
}

function challengeMints(calls: CapturedTgCall[]): any[] {
  // Supabase REST POSTs to telegram_challenges are mint inserts.
  return calls.filter((c) => c.endpoint.includes("/rest/v1/telegram_challenges") && String((c.body as any)?.account_id ?? "").length > 0);
}

function stubFetch(
  calls: CapturedTgCall[],
  routes: (url: string, method: string) => Response | undefined,
  processed?: Set<number>
): typeof fetch {
  return ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? "GET").toUpperCase();
    if (url.includes("api.telegram.org")) {
      calls.push({ endpoint: url, body: init?.body ? JSON.parse(String(init.body)) : {} });
      return Promise.resolve(json({ ok: true }));
    }
    if (url.includes("/rest/v1/telegram_processed_updates")) {
      const body = JSON.parse(String(init?.body ?? "{}")) as { update_id?: number };
      if (processed) {
        if (processed.has(body.update_id ?? -1)) return Promise.resolve(json({ code: "23505" }, 409));
        processed.add(body.update_id ?? -1);
      }
      return Promise.resolve(json({}, 201));
    }
    const routed = routes(url, method);
    if (routed) return Promise.resolve(routed);
    return Promise.resolve(json({ message: "stub" }, 500));
  }) as typeof fetch;
}

Deno.test("a UUID already linked to THIS telegram explains instead of minting another code", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242001;
    const UUID = "a1000000-0000-4000-8000-000000000001";
    const calls: CapturedTgCall[] = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url) => {
      if (url.includes("/rest/v1/telegram_links") && url.includes(`account_id=eq.${UUID}`)) {
        return json({ account_id: UUID, telegram_user_id: CHAT_ID, verified: true });
      }
      return undefined;
    });
    try {
      const update = {
        update_id: 6100,
        message: { message_id: 6100, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: UUID }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(
        tgTexts(calls).some((t) => t.includes("уже привязан")),
        true,
        "the bot must say the account is already linked to this Telegram"
      );
      assertEquals(challengeMints(calls).length, 0, "no new code must be minted for an already-linked account");
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("a UUID linked to ANOTHER telegram identity is refused without minting", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242002;
    const UUID = "a1000000-0000-4000-8000-000000000002";
    const calls: CapturedTgCall[] = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url) => {
      if (url.includes("/rest/v1/telegram_links") && url.includes(`account_id=eq.${UUID}`)) {
        return json({ account_id: UUID, telegram_user_id: 999999, verified: true });
      }
      return undefined;
    });
    try {
      const update = {
        update_id: 6101,
        message: { message_id: 6101, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: UUID }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(
        tgTexts(calls).some((t) => t.includes("другому Telegram")),
        true,
        "the bot must refuse a foreign linked account"
      );
      assertEquals(challengeMints(calls).length, 0);
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/help lists commands and carries an inline keyboard", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242003;
    const calls: CapturedTgCall[] = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, () => undefined);
    try {
      const update = {
        update_id: 6102,
        message: { message_id: 6102, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/help" }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      const texts = tgTexts(calls);
      assertEquals(texts.some((t) => t.includes("/recover")), true, "/help must list /recover");
      assertEquals(texts.some((t) => t.includes("/status")), true, "/help must list /status");
      const keyboard = calls.find((c) => c.endpoint.includes("sendMessage") && c.body.reply_markup);
      assertEquals(!!keyboard, true, "/help must carry an inline keyboard");
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/status reports the linked account username for a linked telegram", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242004;
    const calls: CapturedTgCall[] = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url) => {
      if (url.includes("/rest/v1/telegram_links")) {
        return json({ account_id: "acc-1", telegram_user_id: CHAT_ID, verified: true });
      }
      if (url.includes("/rest/v1/profiles")) return json({ username: "ventaxis" });
      return undefined;
    });
    try {
      const update = {
        update_id: 6103,
        message: { message_id: 6103, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/status" }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      const texts = tgTexts(calls).join("\n");
      assertEquals(texts.includes("ventaxis"), true, "status must show the linked HexShard username");
      assertEquals(texts.includes("привязан"), true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/status explains when nothing is linked", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242005;
    const calls: CapturedTgCall[] = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url) => {
      if (url.includes("/rest/v1/telegram_links")) return json(null);
      return undefined;
    });
    try {
      const update = {
        update_id: 6104,
        message: { message_id: 6104, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/status" }
      };
      const res = await handler(postWithToken(WEBHOOK_SECRET, update));
      assertEquals(res.status, 200);
      assertEquals(tgTexts(calls).some((t) => t.includes("не привязан")), true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("/unlink asks confirmation with an inline button; callback confirms unlink", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242006;
    const calls: CapturedTgCall[] = [];
    let deletes = 0;
    const processed = new Set<number>();
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url, method) => {
      if (url.includes("/rest/v1/telegram_links")) {
        if (method === "DELETE") { deletes++; return new Response(null, { status: 204 }); }
        return json({ account_id: "acc-9", telegram_user_id: CHAT_ID, verified: true });
      }
      return undefined;
    }, processed);
    try {
      const askUpdate = {
        update_id: 6105,
        message: { message_id: 6105, chat: { id: CHAT_ID, type: "private" }, from: { id: CHAT_ID }, text: "/unlink" }
      };
      const askRes = await handler(postWithToken(WEBHOOK_SECRET, askUpdate));
      assertEquals(askRes.status, 200);
      const ask = calls.find((c) => c.endpoint.includes("sendMessage") && c.body.reply_markup);
      assertEquals(!!ask, true, "/unlink must ask via inline keyboard");
      assertEquals(
        JSON.stringify(ask?.body.reply_markup).includes("unlink:yes"),
        true,
        "confirmation must go through the unlink:yes callback"
      );

      const yesUpdate = {
        update_id: 6106,
        callback_query: { id: "cbq1", data: "unlink:yes", from: { id: CHAT_ID }, message: { chat: { id: CHAT_ID, type: "private" } } }
      };
      const yesRes = await handler(postWithToken(WEBHOOK_SECRET, yesUpdate));
      assertEquals(yesRes.status, 200);
      assertEquals(deletes, 1, "confirmed unlink must delete the verified link exactly once");
      assertEquals(
        calls.some((c) => c.endpoint.includes("answerCallbackQuery")),
        true,
        "callbacks must be answered so the button stops spinning"
      );
      assertEquals(tgTexts(calls).some((t) => t.includes("отвязан")), true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});

Deno.test("callback_query updates are deduplicated by update_id like messages", async () => {
  clearSecretEnv();
  Deno.env.set("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET);
  Deno.env.set("TELEGRAM_BOT_TOKEN", "stub-bot-token");
  try {
    const CHAT_ID = 4242007;
    const calls: CapturedTgCall[] = [];
    let deletes = 0;
    const processed = new Set<number>();
    const originalFetch = globalThis.fetch;
    globalThis.fetch = stubFetch(calls, (url, method) => {
      if (url.includes("/rest/v1/telegram_links")) {
        if (method === "DELETE") { deletes++; return new Response(null, { status: 204 }); }
        return json({ account_id: "acc-9", telegram_user_id: CHAT_ID, verified: true });
      }
      return undefined;
    }, processed);
    try {
      const cbUpdate = (uid: number) => ({
        update_id: uid,
        callback_query: { id: `cbq${uid}`, data: "unlink:yes", from: { id: CHAT_ID }, message: { chat: { id: CHAT_ID, type: "private" } } }
      });
      await handler(postWithToken(WEBHOOK_SECRET, cbUpdate(6107)));
      await handler(postWithToken(WEBHOOK_SECRET, cbUpdate(6107)));
      assertEquals(deletes, 1, "the duplicate callback must be skipped");
    } finally {
      globalThis.fetch = originalFetch;
    }
  } finally {
    clearSecretEnv();
  }
});
