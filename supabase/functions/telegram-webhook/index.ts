import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
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
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  // Validate Telegram webhook secret token if configured
  const expectedSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
  if (expectedSecret) {
    const receivedSecret = req.headers.get("x-telegram-bot-api-secret-token");
    if (receivedSecret !== expectedSecret) {
      return new Response("Unauthorized", { status: 401 });
    }
  }

  const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
  if (!botToken) {
    return new Response("Bot token not configured", { status: 500 });
  }

  let body: any;
  try {
    body = await req.json();
  } catch (_e) {
    return new Response("Bad Request", { status: 400 });
  }

  const message = body?.message;
  if (!message || !message.text) {
    return new Response("OK", { status: 200 });
  }

  const text: string = message.text.trim();
  const chatId = message.chat?.id;
  const telegramUserId = message.from?.id;
  const telegramUsername = message.from?.username ?? "";

  const sendTgMessage = async (msg: string) => {
    try {
      await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          chat_id: chatId,
          text: msg,
          parse_mode: "HTML",
        }),
      });
    } catch (_e) {
      // Ignore network errors in reply
    }
  };

  if (text.startsWith("/start")) {
    const parts = text.split(" ");
    const challengeId = parts[1]?.trim();

    if (!challengeId) {
      await sendTgMessage(
        "👋 Welcome to <b>HexShard Messenger Verification</b>.\n\n" +
        "To verify your Telegram account, tap the verification link inside the HexShard app."
      );
      return new Response("OK", { status: 200 });
    }

    const supabase = getServiceClient();

    // Query active challenge
    const { data: challenge, error } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("challenge_id", challengeId)
      .eq("is_used", false)
      .gt("expires_at", new Date().toISOString())
      .single();

    if (error || !challenge) {
      await sendTgMessage(
        "⚠️ <b>Verification link is invalid or has expired.</b>\n\n" +
        "Please generate a new verification link from the HexShard application."
      );
      return new Response("OK", { status: 200 });
    }

    // Generate secure 6-digit code
    const randomBuffer = new Uint32Array(1);
    crypto.getRandomValues(randomBuffer);
    const codeNumber = 100000 + (randomBuffer[0] % 900000);
    const codeString = codeNumber.toString();
    const codeHash = await sha256Hex(codeString);

    // Update challenge with telegram user identity and code hash
    const { error: updateError } = await supabase
      .from("telegram_challenges")
      .update({
        telegram_user_id: telegramUserId,
        telegram_username: telegramUsername,
        code_hash: codeHash,
      })
      .eq("challenge_id", challengeId);

    if (updateError) {
      await sendTgMessage("❌ Error processing verification. Please try again.");
      return new Response("OK", { status: 200 });
    }

    await sendTgMessage(
      `🔐 <b>HexShard Verification Code</b>\n\n` +
      `Your verification code is: <code>${codeString}</code>\n\n` +
      `Enter this code in your HexShard application to link your Telegram identity.\n` +
      `⏱️ This code expires in 5 minutes.`
    );

    return new Response("OK", { status: 200 });
  }

  return new Response("OK", { status: 200 });
});
