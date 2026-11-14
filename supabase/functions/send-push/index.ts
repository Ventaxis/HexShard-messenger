// Push sender: delivers a CONTENT-FREE data ping via FCM HTTP v1. Message
// bodies stay end-to-end encrypted in Supabase — Firebase only learns "ping".
// Caller must be authenticated (their JWT); the recipient token is looked up
// server-side with the service role. FCM_SERVICE_ACCOUNT_JSON secret holds
// the full Firebase service-account JSON for OAuth2 RS256 exchange.
const corsHeaders = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type" };
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SERVICE_JSON = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

interface ServiceAccount { project_id: string; client_email: string; private_key: string; }

function b64url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function getAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const keyData = sa.private_key.replace(/-----[A-Z ]*-----/g, "").replace(/\s+/g, "");
  const raw = Uint8Array.from(atob(keyData), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", raw, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`)));
  const jwt = `${header}.${claims}.${b64url(sig)}`;
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!r.ok) throw new Error(`token exchange failed: ${r.status}`);
  const j = await r.json();
  return j.access_token;
}

async function requireUser(req: Request): Promise<string | null> {
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return null;
  try {
    const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
      headers: { Authorization: auth, apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? "" },
    });
    if (!r.ok) return null;
    const u = await r.json();
    return u?.id ?? null;
  } catch {
    return null;
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "POST only" }), { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
  const uid = await requireUser(req);
  if (!uid) {
    return new Response(JSON.stringify({ error: "Authentication required" }), { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
  if (!SERVICE_JSON) {
    return new Response(JSON.stringify({ error: "FCM not configured" }), { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "Invalid JSON" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
  const recipient = String(body.recipient ?? "");
  const kind = String(body.kind ?? "message");
  const fromName = String(body.from ?? "").slice(0, 64);
  if (!recipient || !["message", "call"].includes(kind)) {
    return new Response(JSON.stringify({ error: "recipient and kind(message|call) required" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }

  const admin = createService();
  const { data: devRow, error } = await admin
    .from("devices")
    .select("fcm_token, platform")
    .eq("account_id", recipient)
    .order("last_seen_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (error) {
    return new Response(JSON.stringify({ error: "Lookup failed" }), { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
  const token = devRow?.fcm_token;
  if (!token) {
    // Recipient has no push token: normal case (app closed, never logged in on
    // a Play-Services device). Not an error — delivery is best effort.
    return new Response(JSON.stringify({ ok: true, delivered: false, reason: "no_token" }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const sa = JSON.parse(SERVICE_JSON) as ServiceAccount;
  const accessToken = await getAccessToken(sa);
  const payload = {
    message: {
      token,
      data: { kind, fromUid: uid, fromName },
      android: { priority: kind === "call" ? "high" : "normal" },
    },
  };
  const send = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const ok = send.ok;
  const detail = ok ? "sent" : (await send.text()).slice(0, 200);
  return new Response(JSON.stringify({ ok, delivered: ok, detail: ok ? undefined : detail }), {
    status: ok ? 200 : 502,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
});

function createService() {
  return createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
}
