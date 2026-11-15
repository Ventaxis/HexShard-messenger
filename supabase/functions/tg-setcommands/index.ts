// One-shot: register the bot command menu via Telegram setMyCommands.
// Gated by an embedded random header token; deleted right after use.
const GATE_TOKEN = "2bfcc9f54fe55e32ed36ea9fa69d201c3a78c4b8c867b62e";

const commands = [
  { command: "start", description: "Привязка аккаунта (токен из приложения)" },
  { command: "help", description: "Меню и кнопки бота" },
  { command: "id", description: "Мой Telegram ID и статус привязки" },
  { command: "status", description: "Статус привязки аккаунта" },
  { command: "recover", description: "Код восстановления входа" },
  { command: "unlink", description: "Отвязать Telegram от аккаунта" },
];

Deno.serve(async (req) => {
  if (req.headers.get("x-hexshard-gate") !== GATE_TOKEN) {
    return new Response("Unauthorized", { status: 401 });
  }
  const botToken = (Deno.env.get("TELEGRAM_BOT_TOKEN") || Deno.env.get("BOT_TOKEN") || "").trim();
  if (!botToken) return new Response("no bot token", { status: 500 });
  const resp = await fetch(`https://api.telegram.org/bot${botToken}/setMyCommands`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ commands, language_code: "ru" }),
  });
  const body = await resp.text();
  const en = await fetch(`https://api.telegram.org/bot${botToken}/setMyCommands`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ commands }),
  });
  return new Response(JSON.stringify({ ru: resp.status, ruBody: body.slice(0, 200), en: en.status }), { status: 200 });
});
