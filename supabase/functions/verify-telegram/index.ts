import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { getAuthenticatedUser, getServiceClient } from "../_shared/supabaseClient.ts";

async function sha256Hex(text: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function whenCodeStatus(code?: string): number {
  switch (code) {
    case "NOT_FOUND": return 404;
    case "MAX_ATTEMPTS": return 429;
    case "ALREADY_BOUND": return 409;
    case "EXPIRED":
    case "ALREADY_USED":
    case "NOT_STARTED":
    case "INVALID_CODE":
    case "INVALID_ARGUMENTS":
      return 400;
    default:
      return 400;
  }
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const user = await getAuthenticatedUser(req);
    if (!user) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: User authentication required" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    let payload: any;
    try {
      payload = await req.json();
    } catch (_e) {
      return new Response(
        JSON.stringify({ error: "Invalid JSON payload" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const challengeId = payload?.challenge_id?.trim();
    const code = payload?.code?.toString().trim();

    if (!challengeId || !code || !/^\d{6}$/.test(code)) {
      return new Response(
        JSON.stringify({ error: "A valid challenge ID and 6-digit verification code are required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabase = getServiceClient();

    // 1. Locate challenge
    // Try primary lookup by challenge_id
    let targetChallenge: any = null;
    const { data: challenge, error: challengeErr } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("challenge_id", challengeId)
      .eq("account_id", user.id)
      .maybeSingle();

    if (!challengeErr && challenge) {
      targetChallenge = challenge;
    } else if (challengeId === user.id) {
      // Fallback: If client passed account_id instead of challenge_id
      const { data: latestChallenge } = await supabase
        .from("telegram_challenges")
        .select("*")
        .eq("account_id", user.id)
        .eq("is_used", false)
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      if (latestChallenge) {
        targetChallenge = latestChallenge;
      }
    }

    if (!targetChallenge) {
      return new Response(
        JSON.stringify({ error: "Verification challenge not found. Please request a new code." }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (targetChallenge.is_used) {
      return new Response(
        JSON.stringify({ error: "This challenge has already been used. Please request a new code." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (new Date(targetChallenge.expires_at) < new Date()) {
      return new Response(
        JSON.stringify({ error: "This challenge has expired. Please request a new code." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (targetChallenge.attempts >= targetChallenge.max_attempts) {
      return new Response(
        JSON.stringify({ error: "Maximum verification attempts exceeded. Please generate a new challenge." }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!targetChallenge.telegram_user_id || !targetChallenge.code_hash) {
      return new Response(
        JSON.stringify({ error: "Please open the Telegram link and start the bot first to receive your code." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Compute SHA-256 hash of the 6-digit code
    const inputHash = await sha256Hex(code);

    // 3. Execute atomic verification and linking inside a single row-locked PostgreSQL transaction
    const { data: rpcResult, error: rpcErr } = await supabase.rpc("verify_telegram_challenge_atomic", {
      p_challenge_id: targetChallenge.challenge_id,
      p_code_hash: inputHash,
      p_account_id: user.id,
    });

    if (rpcErr) {
      console.error("Database error executing verify_telegram_challenge_atomic:", rpcErr);
      return new Response(
        JSON.stringify({ error: "Failed to verify challenge on server database" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!rpcResult || !rpcResult.success) {
      const codeType = rpcResult?.code;
      const message = rpcResult?.message || "Verification failed";
      const status = whenCodeStatus(codeType);

      return new Response(
        JSON.stringify({
          error: message,
          code: codeType,
          remaining_attempts: rpcResult?.remaining_attempts,
        }),
        { status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({
        verified: true,
        telegram_user_id: rpcResult.telegram_user_id,
        telegram_username: rpcResult.telegram_username,
        message: "Telegram successfully verified and linked",
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (e) {
    console.error("Internal server error during verify-telegram:", e);
    return new Response(
      JSON.stringify({ error: "Internal server error during verification" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
