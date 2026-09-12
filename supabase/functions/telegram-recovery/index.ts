import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { getServiceClient } from "../_shared/supabaseClient.ts";

async function sha256Hex(text: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
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

    // STRICT: Never allow recovery by arbitrary telegram_user_id. A valid challenge and 6-digit verification code are mandatory.
    if (!challengeId || !code || !/^\d{6}$/.test(code)) {
      return new Response(
        JSON.stringify({ error: "Challenge ID and valid 6-digit verification code are required for account recovery" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabase = getServiceClient();

    // Query challenge
    const { data: challenge, error } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("challenge_id", challengeId)
      .single();

    if (error || !challenge) {
      return new Response(
        JSON.stringify({ error: "Verification challenge not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (challenge.is_used) {
      return new Response(
        JSON.stringify({ error: "This recovery challenge has already been used. Please request a new one." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (new Date(challenge.expires_at) < new Date()) {
      return new Response(
        JSON.stringify({ error: "This recovery challenge has expired. Please initiate a new challenge." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (challenge.attempts >= challenge.max_attempts) {
      return new Response(
        JSON.stringify({ error: "Maximum verification attempts exceeded for this challenge." }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!challenge.telegram_user_id || !challenge.code_hash) {
      return new Response(
        JSON.stringify({ error: "Please start the Telegram bot first to receive your verification code." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Verify code hash
    const inputHash = await sha256Hex(code);
    if (inputHash !== challenge.code_hash) {
      const nextAttempts = challenge.attempts + 1;
      await supabase
        .from("telegram_challenges")
        .update({ attempts: nextAttempts })
        .eq("challenge_id", challengeId);

      return new Response(
        JSON.stringify({
          error: "Invalid verification code",
          attempts_remaining: Math.max(0, challenge.max_attempts - nextAttempts),
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Mark challenge as consumed
    await supabase
      .from("telegram_challenges")
      .update({ is_used: true, verified_at: new Date().toISOString() })
      .eq("challenge_id", challengeId);

    // Retrieve verified account associated with this Telegram user
    const { data: link, error: linkErr } = await supabase
      .from("telegram_links")
      .select("account_id, verified")
      .eq("telegram_user_id", challenge.telegram_user_id)
      .eq("verified", true)
      .single();

    if (linkErr || !link) {
      return new Response(
        JSON.stringify({ error: "No verified HexShard account linked with this verified Telegram identity" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({
        verified: true,
        account_id: link.account_id,
        message: "Telegram ownership verified successfully via secure server challenge",
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Internal server error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
