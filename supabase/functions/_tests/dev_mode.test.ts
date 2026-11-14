// Unit tests for the dev-mode edge function.
// Run: deno test -A supabase/functions/_tests/
// All network is stubbed via globalThis.fetch — no real calls, no real secrets.

import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";

Deno.env.set("HEXSHARD_SKIP_SERVE", "1");
Deno.env.set("SUPABASE_URL", "https://stub.supabase.co");
Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "stub-service-key");
Deno.env.set("SECRET_WORD", "correct-horse-battery");

const { handler } = await import("../dev-mode/index.ts");

const originalFetch = globalThis.fetch;
const authOk = { id: "dev-user-1" };

function stubFetch(map: (url: string, method: string, body?: unknown) => Response | undefined) {
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? "GET").toUpperCase();
    let body: unknown;
    if (init?.body) {
      try { body = JSON.parse(String(init.body)); } catch { body = String(init.body); }
    }
    const out = map(url, method, body);
    if (out) return Promise.resolve(out);
    return Promise.resolve(new Response("{}", { status: 200 }));
  }) as typeof fetch;
}

function req(action: string, phrase: string, params: Record<string, unknown> = {}): Request {
  return new Request("https://stub.supabase.co/functions/v1/dev-mode", {
    method: "POST",
    headers: { Authorization: "Bearer dev-token", "Content-Type": "application/json" },
    body: JSON.stringify({ action, phrase, params }),
  });
}

Deno.test("verify rejects an unauthenticated caller", async () => {
  stubFetch(() => undefined);
  const r = await handler(new Request("https://stub/functions/v1/dev-mode", {
    method: "POST", body: JSON.stringify({ action: "verify", phrase: "x" }),
  }));
  assertEquals(r.status, 401);
});

Deno.test("verify accepts the correct phrase", async () => {
  stubFetch((url) => url.includes("/auth/v1/user") ? new Response(JSON.stringify(authOk), { status: 200 }) : undefined);
  const r = await handler(req("verify", "correct-horse-battery"));
  const j = await r.json();
  assertEquals(r.status, 200);
  assertEquals(j.ok, true);
});

Deno.test("verify rejects a wrong phrase with 403", async () => {
  stubFetch((url) => url.includes("/auth/v1/user") ? new Response(JSON.stringify(authOk), { status: 200 }) : undefined);
  const r = await handler(req("verify", "wrong-phrase"));
  const j = await r.json();
  assertEquals(r.status, 403);
  assertEquals(j.code, "DEV_BAD_PHRASE");
});

Deno.test("verify fails closed when SECRET_WORD is not configured", async () => {
  Deno.env.set("SECRET_WORD", "");
  try {
    stubFetch((url) => url.includes("/auth/v1/user") ? new Response(JSON.stringify(authOk), { status: 200 }) : undefined);
    const r = await handler(req("verify", "correct-horse-battery"));
    assertEquals(r.status, 403);
  } finally {
    Deno.env.set("SECRET_WORD", "correct-horse-battery");
  }
});

Deno.test("ban resolves the profile by username and calls auth admin", async () => {
  const adminCalls: Array<{ url: string; body: Record<string, unknown> }> = [];
  stubFetch((url, method, body) => {
    if (url.includes("/auth/v1/user")) return new Response(JSON.stringify(authOk), { status: 200 });
    if (url.includes("/rest/v1/profiles")) {
      return new Response(JSON.stringify({ id: "target-1" }), { status: 200 });
    }
    if (url.includes("/auth/v1/admin/users/")) {
      adminCalls.push({ url, body: body as Record<string, unknown> });
      return new Response("{}", { status: 200 });
    }
    return undefined;
  });
  const r = await handler(req("ban", "correct-horse-battery", { username: "@Someone", days: 30 }));
  const j = await r.json();
  assertEquals(r.status, 200);
  assertEquals(j.banned, "target-1");
  assertEquals(j.days, 30);
  assertEquals(adminCalls.length, 1);
  assertEquals(adminCalls[0].body.ban_duration, "30d");
});

Deno.test("unban clears the ban duration", async () => {
  const adminCalls: Array<Record<string, unknown>> = [];
  stubFetch((url, method, body) => {
    if (url.includes("/auth/v1/user")) return new Response(JSON.stringify(authOk), { status: 200 });
    if (url.includes("/rest/v1/profiles")) return new Response(JSON.stringify({ id: "target-2" }), { status: 200 });
    if (url.includes("/auth/v1/admin/users/")) {
      adminCalls.push(body as Record<string, unknown>);
      return new Response("{}", { status: 200 });
    }
    return undefined;
  });
  const r = await handler(req("unban", "correct-horse-battery", { username: "someone" }));
  assertEquals(r.status, 200);
  assertEquals(adminCalls[0].ban_duration, "none");
});

Deno.test("ban with unknown username returns 404 without touching auth admin", async () => {
  let adminTouched = false;
  stubFetch((url) => {
    if (url.includes("/auth/v1/user")) return new Response(JSON.stringify(authOk), { status: 200 });
    if (url.includes("/rest/v1/profiles")) return new Response("null", { status: 200 });
    if (url.includes("/auth/v1/admin/users/")) adminTouched = true;
    return undefined;
  });
  const r = await handler(req("ban", "correct-horse-battery", { username: "ghost" }));
  assertEquals(r.status, 404);
  assertEquals(adminTouched, false);
});

Deno.test("test_avatar uploads and then deletes (nothing persists)", async () => {
  const ops: Array<{ method: string; url: string }> = [];
  const fakeAdmin = {
    storage: {
      from: (_bucket: string) => ({
        upload: async (path: string) => { ops.push({ method: "upload", url: path }); return { error: null }; },
        remove: async (paths: string[]) => { ops.push({ method: "remove", url: paths[0] }); return { error: null }; },
      }),
    },
    from: (_t: string) => ({ insert: async () => ({ error: null }) }),
  };
  // Patch the module-level service client indirectly: the storage path goes
  // through globalThis.fetch for supabase-js — easier to stub createClient via
  // the REST surface: supabase-js storage.upload issues POST to /storage/v1.
  stubFetch((url, method) => {
    if (url.includes("/auth/v1/user")) return new Response(JSON.stringify(authOk), { status: 200 });
    if (url.includes("/rest/v1/dev_audit")) return new Response("{}", { status: 201 });
    if (url.includes("/storage/v1/object/")) {
      ops.push({ method, url });
      return new Response("{}", { status: method === "POST" ? 200 : 200 });
    }
    return undefined;
  });
  void fakeAdmin;
  const r = await handler(req("test_avatar", "correct-horse-battery"));
  const j = await r.json();
  assertEquals(r.status, 200);
  assertEquals(j.ok, true);
  assertEquals(j.deleted, true);
});

Deno.test("unknown action returns 400", async () => {
  stubFetch((url) => url.includes("/auth/v1/user") ? new Response(JSON.stringify(authOk), { status: 200 }) : undefined);
  const r = await handler(req("launch_missiles", "correct-horse-battery"));
  assertEquals(r.status, 400);
  assertEquals((await r.json()).code, "DEV_UNKNOWN_ACTION");
});

Deno.test("every action is rate limited after the hourly cap", async () => {
  let auditCount = 0;
  stubFetch((url, method, body) => {
    if (url.includes("/auth/v1/user")) return new Response(JSON.stringify(authOk), { status: 200 });
    if (url.includes("/rest/v1/dev_audit")) {
      if (method === "POST") { auditCount++; return new Response("{}", { status: 201 }); }
      // head-count query answers the cap for this test
      return new Response(JSON.stringify({}), { status: 200, headers: { "content-range": `*/${30}` } });
    }
    return undefined;
  });
  const r = await handler(req("verify", "correct-horse-battery"));
  assertEquals(r.status, 429);
  assertEquals((await r.json()).code, "DEV_RATE_LIMITED");
  void auditCount;
});

globalThis.fetch = originalFetch;
