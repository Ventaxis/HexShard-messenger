import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { getServiceClient } from "../_shared/supabaseClient.ts";

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

async function sha256Hex(text: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(text);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

serve(async (req: Request) => {
  // Allow GET for webhook health checks
  if (req.method === "GET") {
    return new Response(
      JSON.stringify({ status: "ok", service: "hexshard-telegram-webhook", timestamp: new Date().toISOString() }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }

  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  // 1. Validate Telegram webhook secret token if configured
  const expectedSecret = (Deno.env.get("TELEGRAM_WEBHOOK_SECRET") || Deno.env.get("WEBHOOK_SECRET"))?.trim();
  if (expectedSecret) {
    const receivedSecret = req.headers.get("x-telegram-bot-api-secret-token")?.trim();
    if (!receivedSecret || !constantTimeEqual(receivedSecret, expectedSecret)) {
      console.warn("Unauthorized webhook request: missing or mismatched X-Telegram-Bot-Api-Secret-Token");
      return new Response("Unauthorized", { status: 401 });
    }
  }

  // 2. Validate Telegram Bot Token
  const botToken = (
    Deno.env.get("TELEGRAM_BOT_TOKEN") ||
    Deno.env.get("BOT_TOKEN") ||
    Deno.env.get("HEXSHARD_TELEGRAM_BOT_TOKEN")
  )?.trim();

  if (!botToken) {
    console.error("TELEGRAM_BOT_TOKEN is not configured in Supabase Edge Functions secrets");
    return new Response(
      JSON.stringify({ error: "TELEGRAM_BOT_TOKEN not configured" }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  // 3. Parse incoming webhook payload
  let body: any;
  try {
    body = await req.json();
  } catch (_e) {
    return new Response("Bad Request: Invalid JSON", { status: 400 });
  }

  const updateId = body?.update_id;
  const message = body?.message || body?.edited_message;
  if (!message || !message.text) {
    // Non-text message or update type (e.g. inline query, reaction); acknowledge with 200
    return new Response("OK", { status: 200 });
  }

  const chatType = message.chat?.type;
  const rawText: string = message.text.trim();
  const chatId = message.chat?.id;
  const telegramUserId = message.from?.id;
  const telegramUsername = message.from?.username ?? "";

  if (!chatId || !telegramUserId) {
    return new Response("OK", { status: 200 });
  }

  // F009: Strict check - only process verifications in private direct messages with the bot
  if (chatType && chatType !== "private") {
    // Avoid sending verification codes into groups or channels
    try {
      await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          chat_id: chatId,
          text: "⚠️ <b>HexShard Messenger:</b> Пожалуйста, откройте личные сообщения с ботом (@HexShardBot) для подтверждения аккаунта.",
          parse_mode: "HTML",
        }),
      });
    } catch (_e) {}
    return new Response("OK", { status: 200 });
  }

  const supabase = getServiceClient();

  // F008: Deduplicate incoming updates using telegram_processed_updates
  if (updateId != null) {
    const { error: insErr } = await supabase
      .from("telegram_processed_updates")
      .insert({ update_id: updateId });
    if (insErr && (insErr.code === "23505" || insErr.message?.includes("duplicate"))) {
      console.log(`Update ${updateId} already processed; skipping to prevent duplicate processing`);
      return new Response("OK", { status: 200 });
    }
  }

  const sendTgMessage = async (msg: string) => {
    try {
      const resp = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          chat_id: chatId,
          text: msg,
          parse_mode: "HTML",
        }),
      });
      if (!resp.ok) {
        const errText = await resp.text();
        console.error("Failed to send message via Telegram Bot API:", resp.status, errText);
      }
    } catch (e) {
      console.error("Network error when calling Telegram Bot API:", e);
    }
  };

  // 4. Extract token / challenge ID
  let tokenParam = "";
  if (rawText.startsWith("/start")) {
    const match = rawText.match(/^\/start(?:[=\s_]+(\S+))?/i);
    tokenParam = match?.[1]?.trim() || "";
  } else if (/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(rawText)) {
    // Direct UUID sent by user
    tokenParam = rawText;
  } else if (/^[a-f0-9]{24,64}$/i.test(rawText)) {
    // Direct hex challenge token sent by user
    tokenParam = rawText;
  }

  if (!tokenParam) {
    await sendTgMessage(
      "👋 <b>HexShard Messenger Verification Bot (@HexShardBot)</b>\n\n" +
      "Для подтверждения и привязки вашего аккаунта Telegram к HexShard Messenger:\n" +
      "1. Откройте приложение <b>HexShard Messenger</b>.\n" +
      "2. На шаге подтверждения или в Настройках нажмите <b>«Открыть @HexShardBot»</b>.\n" +
      "3. Нажмите <b>«Запустить» (Start)</b> для получения 6-значного кода.\n\n" +
      "<i>To link your Telegram account, please use the verification button inside HexShard Messenger.</i>"
    );
    return new Response("OK", { status: 200 });
  }

  // 5. Locate challenge in database
  // Step A: Search by challenge_id
  let targetChallenge: any = null;

  const { data: challengeById, error: errById } = await supabase
    .from("telegram_challenges")
    .select("*")
    .eq("challenge_id", tokenParam)
    .maybeSingle();

  if (!errById && challengeById) {
    targetChallenge = challengeById;
  }

  // Step B: If not found, check if tokenParam is an account_id (UUID)
  const isUuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(tokenParam);
  if (!targetChallenge && isUuid) {
    const { data: challengeByAccount } = await supabase
      .from("telegram_challenges")
      .select("*")
      .eq("account_id", tokenParam)
      .eq("is_used", false)
      .gt("expires_at", new Date().toISOString())
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (challengeByAccount) {
      targetChallenge = challengeByAccount;
    } else {
      // Step C: Check if account exists in profiles; if so, dynamically create a challenge
      const { data: profile } = await supabase
        .from("profiles")
        .select("id")
        .eq("id", tokenParam)
        .maybeSingle();

      if (profile) {
        const randomBytes = new Uint8Array(24);
        crypto.getRandomValues(randomBytes);
        const dynamicChallengeId = Array.from(randomBytes).map((b) => b.toString(16).padStart(2, "0")).join("");
        const dynamicExpires = new Date(Date.now() + 5 * 60 * 1000).toISOString();

        const { data: newChallenge, error: createErr } = await supabase
          .from("telegram_challenges")
          .insert({
            challenge_id: dynamicChallengeId,
            account_id: profile.id,
            expires_at: dynamicExpires,
            attempts: 0,
            max_attempts: 5,
            is_used: false,
          })
          .select("*")
          .single();

        if (!createErr && newChallenge) {
          targetChallenge = newChallenge;
        }
      }
    }
  }

  if (!targetChallenge) {
    await sendTgMessage(
      "⚠️ <b>Ссылка верификации не найдена или недействительна.</b>\n\n" +
      "Пожалуйста, создайте новую ссылку в приложении HexShard Messenger.\n\n" +
      "<i>Verification link was not found or is invalid. Please request a new code in HexShard.</i>"
    );
    return new Response("OK", { status: 200 });
  }

  // 6. Check challenge state
  if (targetChallenge.is_used) {
    await sendTgMessage(
      "⚠️ <b>Эта ссылка уже была использована.</b>\n\n" +
      "Для повторной привязки или обновления запросите новый код в приложении HexShard.\n\n" +
      "<i>This verification link has already been used. Please generate a new one.</i>"
    );
    return new Response("OK", { status: 200 });
  }

  if (new Date(targetChallenge.expires_at) < new Date()) {
    await sendTgMessage(
      "⏱️ <b>Срок действия ссылки истек.</b>\n\n" +
      "Запросите новый код в приложении HexShard Messenger.\n\n" +
      "<i>This verification link has expired. Please request a new code in HexShard.</i>"
    );
    return new Response("OK", { status: 200 });
  }

  if (targetChallenge.attempts >= targetChallenge.max_attempts) {
    await sendTgMessage(
      "⚠️ <b>Превышено максимальное число попыток ввода кода.</b>\n\n" +
      "Пожалуйста, сгенерируйте новый запрос в приложении HexShard.\n\n" +
      "<i>Maximum verification attempts exceeded. Please generate a new challenge.</i>"
    );
    return new Response("OK", { status: 200 });
  }

  // 7. Generate cryptographically secure 6-digit code
  const randomBuffer = new Uint32Array(1);
  crypto.getRandomValues(randomBuffer);
  const codeNumber = 100000 + (randomBuffer[0] % 900000);
  const codeString = codeNumber.toString();
  const codeHash = await sha256Hex(codeString);

  // Extend expiration for a full 5 minutes from code delivery
  const codeExpiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();

  // 8. Update challenge with Telegram identity, hashed code, and refreshed expiry
  const { error: updateError } = await supabase
    .from("telegram_challenges")
    .update({
      telegram_user_id: telegramUserId,
      telegram_username: telegramUsername || null,
      code_hash: codeHash,
      attempts: 0,
      expires_at: codeExpiresAt,
    })
    .eq("challenge_id", targetChallenge.challenge_id)
    .eq("is_used", false);

  if (updateError) {
    console.error("Database error while updating telegram challenge:", updateError);
    await sendTgMessage("❌ Ошибка обработки верификации на сервере. Пожалуйста, попробуйте еще раз.");
    return new Response("OK", { status: 200 });
  }

  // 9. Deliver verification code to user in Telegram
  await sendTgMessage(
    `🔐 <b>HexShard Messenger — Код подтверждения</b>\n\n` +
    `Ваш 6-значный код: <code>${codeString}</code>\n\n` +
    `Введите этот код в приложении HexShard для завершения привязки Telegram.\n` +
    `⏱️ Код действует 5 минут.\n\n` +
    `<i>Your verification code is: <code>${codeString}</code> (expires in 5 minutes).</i>`
  );

  return new Response("OK", { status: 200 });
});
