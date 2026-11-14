// Developer-mode gate and actions (owner request 2026-11-14).
//
// The secret phrase NEVER ships in the APK: the client sends it on every call
// and this function compares it against the SECRET_WORD edge secret with a
// timing-safe digest comparison. Every call is authenticated, rate limited and
// audited into dev_audit (service-role only). Test actions clean up after
// themselves so nothing accumulates on the server.

// @ts-nocheck — keep runtime parity with sibling functions under Deno deploy.
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

const DEV_RATE_LIMIT_PER_HOUR = 30;
const DEV_TTL_SECONDS = 3600;
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function errJson(status: number, code: string, message: string): Response {
  return new Response(
    JSON.stringify({ error: message, code }),
    { status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
  );
}

async function sha256Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison over equal-length digests. */
function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function verifyPhrase(phrase: string): Promise<boolean> {
  const expected = Deno.env.get("SECRET_WORD");
  if (!expected || expected.trim() === "") return false;
  const [a, b] = await Promise.all([sha256Hex(phrase ?? ""), sha256Hex(expected.trim())]);
  return timingSafeEqualHex(a, b);
}

async function requireUser(req: Request): Promise<{ uid: string } | null> {
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return null;
  try {
    const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
      headers: { Authorization: auth, apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? "" },
    });
    if (!r.ok) return null;
    const user = await r.json();
    return user?.id ? { uid: user.id } : null;
  } catch {
    return null;
  }
}

function serviceClient() {
  return createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
}

async function rateLimited(uid: string): Promise<boolean> {
  const admin = serviceClient();
  const since = new Date(Date.now() - 3600_000).toISOString();
  const { count } = await admin
    .from("dev_audit")
    .select("id", { count: "exact", head: true })
    .eq("account_id", uid)
    .gte("created_at", since);
  return (count ?? 0) >= DEV_RATE_LIMIT_PER_HOUR;
}

async function audit(uid: string, action: string, details: Record<string, unknown>): Promise<void> {
  try {
    await serviceClient().from("dev_audit").insert({ account_id: uid, action, details });
  } catch {
    // audit must never break the action
  }
}

async function findUserIdByUsername(username: string): Promise<string | null> {
  const admin = serviceClient();
  const clean = username.trim().replace(/^@/, "").toLowerCase();
  const { data } = await admin.from("profiles").select("id").eq("normalized_username", clean).maybeSingle();
  return data?.id ?? null;
}

async function banUser(uid: string, params: Record<string, unknown>): Promise<Response> {
  const username = String(params.username ?? "");
  const days = Math.min(Math.max(Number(params.days ?? 7), 1), 3650);
  const target = await findUserIdByUsername(username);
  if (!target) return errJson(404, "DEV_USER_NOT_FOUND", "User not found");
  const r = await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${target}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${SERVICE_KEY}`, "Content-Type": "application/json", apikey: SERVICE_KEY },
    body: JSON.stringify({ ban_duration: `${days}d` }),
  });
  if (!r.ok) return errJson(502, "DEV_BAN_FAILED", `Auth admin returned ${r.status}`);
  await audit(uid, "ban", { target, days, username: username.toLowerCase() });
  return new Response(JSON.stringify({ ok: true, banned: target, days }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function unbanUser(uid: string, params: Record<string, unknown>): Promise<Response> {
  const username = String(params.username ?? "");
  const target = await findUserIdByUsername(username);
  if (!target) return errJson(404, "DEV_USER_NOT_FOUND", "User not found");
  const r = await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${target}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${SERVICE_KEY}`, "Content-Type": "application/json", apikey: SERVICE_KEY },
    body: JSON.stringify({ ban_duration: "none" }),
  });
  if (!r.ok) return errJson(502, "DEV_UNBAN_FAILED", `Auth admin returned ${r.status}`);
  await audit(uid, "unban", { target, username: username.toLowerCase() });
  return new Response(JSON.stringify({ ok: true, unbanned: target }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function listBanned(uid: string): Promise<Response> {
  const r = await fetch(`${SUPABASE_URL}/auth/v1/admin/users?per_page=200`, {
    headers: { Authorization: `Bearer ${SERVICE_KEY}`, apikey: SERVICE_KEY },
  });
  if (!r.ok) return errJson(502, "DEV_LIST_FAILED", `Auth admin returned ${r.status}`);
  const body = await r.json();
  const banned = (body?.users ?? [])
    .filter((u: Record<string, unknown>) => !!u.banned_until)
    .map((u: Record<string, unknown>) => ({
      id: u.id,
      username: (u.user_metadata as Record<string, unknown> | undefined)?.username ?? null,
      banned_until: u.banned_until,
    }));
  await audit(uid, "list_banned", { count: banned.length });
  return new Response(JSON.stringify({ ok: true, banned }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/** Upload-then-delete roundtrip: proves storage works, leaves nothing behind. */
async function testAvatar(uid: string): Promise<Response> {
  const admin = serviceClient();
  const path = `dev-test/${crypto.randomUUID()}.jpg`;
  const bytes = new Uint8Array([0x2f, 0x2f, 0x2f, 0x2f]); // placeholder bytes, never served
  const up = await admin.storage.from("avatars").upload(path, bytes, { contentType: "image/jpeg", upsert: false });
  if (up.error) return errJson(502, "DEV_UPLOAD_FAILED", up.error.message);
  const del = await admin.storage.from("avatars").remove([path]);
  await audit(uid, "test_avatar", { path, deleted: !del.error });
  if (del.error) return errJson(502, "DEV_CLEANUP_FAILED", "Uploaded but cleanup failed: " + del.error.message);
  return new Response(JSON.stringify({ ok: true, uploaded_bytes: bytes.length, deleted: true }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export async function handler(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return errJson(405, "DEV_METHOD", "POST only");

  const user = await requireUser(req);
  if (!user) return errJson(401, "DEV_UNAUTHORIZED", "Authentication required");

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errJson(400, "DEV_BAD_BODY", "Invalid JSON");
  }
  const action = String(body.action ?? "");
  const phrase = String(body.phrase ?? "");
  const params = (body.params as Record<string, unknown>) ?? {};

  // Review F1 (HIGH): the limiter MUST run before phrase verification —
  // otherwise failed attempts are never throttled and SECRET_WORD can be
  // brute-forced online. verify_failed rows count toward the same cap.
  if (await rateLimited(user.uid)) {
    return errJson(429, "DEV_RATE_LIMITED", "Too many developer actions this hour");
  }
  if (!(await verifyPhrase(phrase))) {
    await audit(user.uid, "verify_failed", { action });
    return errJson(403, "DEV_BAD_PHRASE", "Invalid developer phrase");
  }

  switch (action) {
    case "verify":
      await audit(user.uid, "verify", {});
      return new Response(JSON.stringify({ ok: true, ttl_seconds: DEV_TTL_SECONDS }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    case "ban":
      return banUser(user.uid, params);
    case "unban":
      return unbanUser(user.uid, params);
    case "list_banned":
      return listBanned(user.uid);
    case "test_avatar":
      return testAvatar(user.uid);
    default:
      return errJson(400, "DEV_UNKNOWN_ACTION", `Unknown action: ${action}`);
  }
}

// Do not bind a port when imported for unit tests; the handler is called directly there.
if (Deno.env.get("HEXSHARD_SKIP_SERVE") !== "1") {
  serve(handler);
}
