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

export async function handler(req: Request): Promise<Response> {
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

    const ticketId = payload?.ticket_id?.trim();
    const challengeId = payload?.challenge_id?.trim();
    const code = payload?.code?.toString().trim();

    const supabase = getServiceClient();

    if (ticketId) {
      // S-7: recovery-ticket flow — consumes a one-time bearer ticket minted by the bot's
      // /recover command. Linking challenges (telegram_challenges) are never touched by
      // this path, so recovery can no longer burn a pending linking challenge.
      if (!/^[a-f0-9]{24,64}$/i.test(ticketId)) {
        return new Response(
          JSON.stringify({ error: "Invalid recovery ticket format" }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      const { data: ticket, error: ticketErr } = await supabase
        .from("account_recovery_tickets")
        .select("*")
        .eq("ticket_id", ticketId)
        .maybeSingle();
      if (ticketErr || !ticket) {
        return new Response(
          JSON.stringify({ error: "Recovery ticket not found" }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (ticket.is_used) {
        return new Response(
          JSON.stringify({ error: "This recovery ticket has already been used." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (new Date(ticket.expires_at) < new Date()) {
        return new Response(
          JSON.stringify({ error: "This recovery ticket has expired." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      const { data: usedRows, error: usedErr } = await supabase
        .from("account_recovery_tickets")
        .update({ is_used: true })
        .eq("ticket_id", ticketId)
        .eq("is_used", false)
        .select("ticket_id");
      if (usedErr || !usedRows || usedRows.length === 0) {
        return new Response(
          JSON.stringify({ error: "This recovery ticket has already been used." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      return new Response(
        JSON.stringify({ verified: true, account_id: ticket.account_id, method: "recovery_ticket" }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // STRICT: Never allow recovery by arbitrary telegram_user_id. A valid challenge and 6-digit verification code are mandatory.
    if (!challengeId || !code || !/^\d{6}$/.test(code)) {
      return new Response(
        JSON.stringify({ error: "Challenge ID and valid 6-digit verification code are required for account recovery" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Query challenge
    const { data: challenge, error } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("challenge_id", challengeId)
      .maybeSingle();

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
      // Atomic CAS bump: increments only while the stored attempts still equals the value we
      // read AND stays below max_attempts. A parallel guess re-reads and retries, so the cap
      // can never be raced past (closes the TOCTOU of the old read-then-write).
      let currentAttempts = challenge.attempts as number;
      const maxAttempts = challenge.max_attempts as number;
      let bumped = false;
      for (let round = 0; round < 5 && currentAttempts < maxAttempts; round++) {
        const { data: bumpedRows, error: bumpErr } = await supabase
          .from("telegram_challenges")
          .update({ attempts: currentAttempts + 1 })
          .eq("challenge_id", challengeId)
          .eq("attempts", currentAttempts)
          .eq("is_used", false)
          .lt("attempts", maxAttempts)
          .select("attempts");
        if (bumpErr) break;
        if (bumpedRows && bumpedRows.length > 0) {
          currentAttempts = bumpedRows[0].attempts as number;
          bumped = true;
          break;
        }
        // Lost the CAS race — re-read the current counter and retry.
        const { data: fresh } = await supabase
          .from("telegram_challenges")
          .select("attempts")
          .eq("challenge_id", challengeId)
          .maybeSingle();
        if (!fresh) break;
        currentAttempts = fresh.attempts as number;
      }

      if (!bumped) {
        return new Response(
          JSON.stringify({ error: "Maximum verification attempts exceeded for this challenge." }),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      return new Response(
        JSON.stringify({
          error: "Invalid verification code",
          attempts_remaining: Math.max(0, maxAttempts - currentAttempts),
        }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Mark challenge as consumed atomically
    const { data: updatedRows, error: updateErr } = await supabase
      .from("telegram_challenges")
      .update({ is_used: true })
      .eq("challenge_id", challengeId)
      .eq("is_used", false)
      .select("challenge_id");

    if (updateErr || !updatedRows || updatedRows.length === 0) {
      return new Response(
        JSON.stringify({ error: "This recovery challenge has already been used or expired." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Retrieve verified account associated with this Telegram user
    const { data: link, error: linkErr } = await supabase
      .from("telegram_links")
      .select("account_id, verified")
      .eq("telegram_user_id", challenge.telegram_user_id)
      .eq("verified", true)
      .maybeSingle();

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
  } catch (e) {
    console.error("Internal server error in telegram-recovery:", e);
    return new Response(
      JSON.stringify({ error: "Internal server error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

// Do not bind a port when imported for unit tests; the handler is called directly there.
if (Deno.env.get("HEXSHARD_SKIP_SERVE") !== "1") {
  serve(handler);
}
