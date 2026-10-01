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

    // Query challenge
    const { data: challenge, error } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("challenge_id", challengeId)
      .eq("account_id", user.id)
      .single();

    if (error || !challenge) {
      return new Response(
        JSON.stringify({ error: "Verification challenge not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (challenge.is_used) {
      return new Response(
        JSON.stringify({ error: "This challenge has already been used. Please request a new one." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (new Date(challenge.expires_at) < new Date()) {
      return new Response(
        JSON.stringify({ error: "This challenge has expired. Please request a new code." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (challenge.attempts >= challenge.max_attempts) {
      return new Response(
        JSON.stringify({ error: "Maximum verification attempts exceeded. Please generate a new challenge." }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!challenge.telegram_user_id || !challenge.code_hash) {
      return new Response(
        JSON.stringify({ error: "Please open the Telegram link and start the bot first to receive your code." }),
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

      const remaining = challenge.max_attempts - nextAttempts;
      return new Response(
        JSON.stringify({
          error: `Invalid verification code. ${remaining > 0 ? `${remaining} attempts remaining.` : "No attempts remaining."}`,
          remaining_attempts: remaining,
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Atomically mark challenge as used
    const { data: updatedRows, error: updateUseErr } = await supabase
      .from("telegram_challenges")
      .update({ is_used: true })
      .eq("challenge_id", challengeId)
      .eq("is_used", false)
      .select("challenge_id");

    if (updateUseErr || !updatedRows || updatedRows.length === 0) {
      return new Response(
        JSON.stringify({ error: "This challenge has already been used or expired." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Upsert verified link
    const { error: linkError } = await supabase.from("telegram_links").upsert(
      {
        account_id: user.id,
        telegram_user_id: challenge.telegram_user_id,
        telegram_username: challenge.telegram_username,
        verified: true,
        updated_at: new Date().toISOString(),
      },
      { onConflict: "account_id" }
    );

    if (linkError) {
      return new Response(
        JSON.stringify({ error: "Failed to bind Telegram identity to account" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({
        verified: true,
        telegram_user_id: challenge.telegram_user_id,
        telegram_username: challenge.telegram_username,
        message: "Telegram successfully verified and linked",
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Internal server error during verification" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
