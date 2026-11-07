// Unit tests for the telegram-recovery edge function.
// Run: deno test -A supabase/functions/_tests/
// Supabase REST is stubbed via globalThis.fetch — no real calls are made.

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("SUPABASE_URL", "https://stub.supabase.co");
Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "stub-service-key");

const { handler } = await import("../telegram-recovery/index.ts");

async function sha256Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

const GOOD_CODE = "999999";
const CHALLENGE_ID = "ch-test-1";

function challengeRow(overrides: Record<string, unknown> = {}) {
  return {
    challenge_id: CHALLENGE_ID,
    account_id: "acc-1",
    telegram_user_id: "tg777",
    code_hash: undefined as string | undefined, // filled after sha256 below
    attempts: 0,
    max_attempts: 5,
    is_used: false,
    expires_at: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    ...overrides,
  };
}

interface CapturedPatch {
  url: string;
  body: Record<string, unknown>;
}

const patchCalls: CapturedPatch[] = [];
const originalFetch = globalThis.fetch;

function installFetchStub(row: Record<string, unknown>, opts: { linksRow?: Record<string, unknown> | null } = {}) {
  patchCalls.length = 0;
  const stub = (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? "GET").toUpperCase();
    const json = (obj: unknown, status = 200) =>
      Promise.resolve(new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } }));

    if (url.includes("/rest/v1/telegram_challenges")) {
      if (method === "GET") return json(row);
      if (method === "PATCH") {
        const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
        patchCalls.push({ url, body });
        const attempts = typeof body.attempts === "number" ? body.attempts : (row.attempts as number);
        return json([{ attempts }]);
      }
    }
    if (url.includes("/rest/v1/telegram_links")) {
      return json(opts.linksRow === undefined ? { account_id: "acc-1", verified: true } : opts.linksRow);
    }
    return json({ message: "stub" }, 500);
  };
  globalThis.fetch = stub as typeof fetch;
}

function restoreFetch() {
  globalThis.fetch = originalFetch;
}

function recoveryRequest(code: string): Request {
  return new Request("https://project.functions.supabase.co/telegram-recovery", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ challenge_id: CHALLENGE_ID, code })
  });
}

Deno.test("wrong code bumps attempts via conditional CAS update (no TOCTOU)", async () => {
  const row = challengeRow();
  row.code_hash = await sha256Hex(GOOD_CODE);
  installFetchStub(row);
  try {
    const res = await handler(recoveryRequest("111111"));
    assertEquals(res.status, 400);
    const json = await res.json();
    assertEquals(json.attempts_remaining, 4);
    assertEquals(patchCalls.length, 1, "exactly one atomic attempt-bump expected");
    const patch = patchCalls[0];
    // The CAS conditions: only bump when the stored attempts still equals what we read,
    // and never past max_attempts. Without these a parallel guess-race bypasses the cap.
    assertEquals(patch.url.includes("attempts=eq.0"), true, "CAS guard: attempts=eq.<read value>");
    assertEquals(patch.url.includes("attempts=lt.5"), true, "CAS guard: attempts=lt.<max_attempts>");
    assertEquals(patch.body.attempts, 1);
  } finally {
    restoreFetch();
  }
});

Deno.test("exhausted attempts are rejected with 429 before code comparison", async () => {
  const row = challengeRow({ attempts: 5, max_attempts: 5 });
  row.code_hash = await sha256Hex(GOOD_CODE);
  installFetchStub(row);
  try {
    const res = await handler(recoveryRequest(GOOD_CODE));
    assertEquals(res.status, 429);
    assertEquals(patchCalls.length, 0);
  } finally {
    restoreFetch();
  }
});

Deno.test("correct code consumes the challenge conditionally and returns the linked account", async () => {
  const row = challengeRow();
  row.code_hash = await sha256Hex(GOOD_CODE);
  installFetchStub(row);
  try {
    const res = await handler(recoveryRequest(GOOD_CODE));
    assertEquals(res.status, 200);
    const json = await res.json();
    assertEquals(json.verified, true);
    assertEquals(json.account_id, "acc-1");
    const consume = patchCalls[0];
    assertEquals(consume.url.includes("is_used=eq.false"), true, "single-use must be a conditional update");
    assertEquals(consume.body.is_used, true);
  } finally {
    restoreFetch();
  }
});

Deno.test("already used challenge is rejected", async () => {
  const row = challengeRow({ is_used: true });
  row.code_hash = await sha256Hex(GOOD_CODE);
  installFetchStub(row);
  try {
    const res = await handler(recoveryRequest(GOOD_CODE));
    assertEquals(res.status, 400);
  } finally {
    restoreFetch();
  }
});

Deno.test("no verified telegram link yields 404, not an account leak", async () => {
  const row = challengeRow();
  row.code_hash = await sha256Hex(GOOD_CODE);
  installFetchStub(row, { linksRow: null });
  try {
    const res = await handler(recoveryRequest(GOOD_CODE));
    assertEquals(res.status, 404);
    const json = await res.json();
    assertEquals(json.account_id, undefined);
  } finally {
    restoreFetch();
  }
});

// --- Recovery-ticket flow (S-7): separate from linking challenges ---

Deno.test("valid recovery ticket is consumed via CAS and returns the linked account", async () => {
  installFetchStub({});
  try {
    // Point the stub at the ticket table instead of the challenge table.
    globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      const json = (obj: unknown, status = 200) =>
        Promise.resolve(new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } }));
      if (url.includes("/rest/v1/account_recovery_tickets")) {
        if (method === "GET") {
          return json({
            ticket_id: "a".repeat(48),
            account_id: "acc-ticket-1",
            telegram_user_id: 987654321,
            ticket_hash: "x".repeat(64),
            is_used: false,
            expires_at: new Date(Date.now() + 10 * 60 * 1000).toISOString()
          });
        }
        if (method === "PATCH") {
          patchCalls.push({ url, body: JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown> });
          return json([{ ticket_id: "a".repeat(48) }]);
        }
      }
      return json({ message: "stub" }, 500);
    }) as typeof fetch;
    try {
      const req = new Request("https://project.functions.supabase.co/telegram-recovery", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ ticket_id: "a".repeat(48) })
      });
      const res = await handler(req);
      assertEquals(res.status, 200);
      const json = await res.json();
      assertEquals(json.verified, true);
      assertEquals(json.account_id, "acc-ticket-1");
      assertEquals(patchCalls.length, 1);
      assertEquals(patchCalls[0].url.includes("is_used=eq.false"), true, "ticket consumption must be conditional");
      assertEquals(patchCalls[0].body.is_used, true);
    } finally {
      restoreFetch();
    }
  } finally {
    restoreFetch();
  }
});

Deno.test("used recovery ticket is rejected", async () => {
  const json = (obj: unknown, status = 200) =>
    Promise.resolve(new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } }));
  globalThis.fetch = ((input: string | URL | Request) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("/rest/v1/account_recovery_tickets")) {
      return json({
        ticket_id: "b".repeat(48), account_id: "acc-1", is_used: true,
        expires_at: new Date(Date.now() + 10 * 60 * 1000).toISOString()
      });
    }
    return json({ message: "stub" }, 500);
  }) as typeof fetch;
  try {
    const res = await handler(new Request("https://x/telegram-recovery", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ ticket_id: "b".repeat(48) })
    }));
    assertEquals(res.status, 400);
  } finally {
    restoreFetch();
  }
});

Deno.test("unknown recovery ticket yields 404 without leaking accounts", async () => {
  const json = (obj: unknown, status = 200) =>
    Promise.resolve(new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } }));
  globalThis.fetch = ((input: string | URL | Request) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("/rest/v1/account_recovery_tickets")) return json(null);
    return json({ message: "stub" }, 500);
  }) as typeof fetch;
  try {
    const res = await handler(new Request("https://x/telegram-recovery", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ ticket_id: "c".repeat(48) })
    }));
    assertEquals(res.status, 404);
    const json2 = await res.json();
    assertEquals(json2.account_id, undefined);
  } finally {
    restoreFetch();
  }
});
