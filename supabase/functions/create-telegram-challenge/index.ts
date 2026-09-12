import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { getAuthenticatedUser, getServiceClient } from "../_shared/supabaseClient.ts";

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const user = await getAuthenticatedUser(req);
    if (!user) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: Valid bearer token required" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabase = getServiceClient();

    // Check rate limit: maximum 3 active challenges created in last 5 minutes
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString();
    const { count, error: countError } = await supabase
      .from("telegram_challenges")
      .select("*", { count: "exact", head: true })
      .eq("account_id", user.id)
      .gt("created_at", fiveMinutesAgo);

    if (countError) {
      return new Response(
        JSON.stringify({ error: "Database error checking rate limit" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (count && count >= 5) {
      return new Response(
        JSON.stringify({ error: "Rate limit exceeded. Please wait a few minutes before requesting another challenge." }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Generate cryptographically secure random challenge ID
    const randomBytes = new Uint8Array(24);
    crypto.getRandomValues(randomBytes);
    const challengeId = Array.from(randomBytes).map((b) => b.toString(16).padStart(2, "0")).join("");

    const expiresAt = new Date(Date.now() + 5 * 60 * 1000); // 5 minutes TTL

    // Invalidate prior unused challenges for this user
    await supabase
      .from("telegram_challenges")
      .update({ is_used: true })
      .eq("account_id", user.id)
      .eq("is_used", false);

    // Insert new challenge (code_hash will be assigned when user opens Telegram bot)
    const { error: insertError } = await supabase.from("telegram_challenges").insert({
      challenge_id: challengeId,
      account_id: user.id,
      expires_at: expiresAt.toISOString(),
      attempts: 0,
      max_attempts: 5,
      is_used: false,
    });

    if (insertError) {
      return new Response(
        JSON.stringify({ error: "Failed to persist challenge" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const botUsername = Deno.env.get("TELEGRAM_BOT_USERNAME") ?? "HexShardBot";
    const deepLink = `https://t.me/${botUsername}?start=${challengeId}`;

    // Security requirement: NEVER return verification code or secrets to client
    return new Response(
      JSON.stringify({
        challenge_id: challengeId,
        deep_link: deepLink,
        expires_at: expiresAt.getTime(),
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
