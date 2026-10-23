// Подпись ссылки подписки и сборка тела подписки.
//
// Модуль вынесен отдельно, потому что те же правила должны работать в двух
// функциях: telegram-bot подписывает ссылку, telegram-subscription её
// проверяет. Кросс-импорт между каталогами supabase/functions/* не
// деплоится, поэтому правило продублировано, а одинаковое поведение
// закреплено тестами в обоих каталогах.

export const TOKEN_LENGTH = 32;

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function hmacHex(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret || "mirage-subscription"),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return toHex(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)));
}

// Подпись Telegram-аккаунта: HMAC-SHA256 от tg_id, первые 32 hex-символа.
//
// version = 0 — прежняя подпись: после деплоя действующие ссылки работают
// как раньше. Сброс ссылки увеличивает версию, и старые адреса перестают
// проходить проверку, а бот собирает новую ссылку уже с другой подписью.
export async function subscriptionSignature(
  tgId: number,
  secret: string,
  version = 0,
): Promise<string> {
  const payload = version > 0 ? `${tgId}:${version}` : String(tgId);
  const hex = await hmacHex(payload, secret);
  return hex.slice(0, TOKEN_LENGTH);
}

// freeToken — отдельная подпись бесплатного узла.
//
// Флаг вроде ?free=1 не годится: обычная ссылка подписки видна в адресе
// страницы подключения, и флаг мог бы дописать любой. Бесплатный узел
// открывается только по подписи, которую знает бот и сервер.
// Правило продублировано в telegram-bot/lib/subscription.ts.
export async function freeToken(
  tgId: number,
  secret: string,
): Promise<string> {
  const hex = await hmacHex(`${tgId}:free`, secret);
  return hex.slice(0, TOKEN_LENGTH);
}

export function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

// parseSignedUser — достаёт tg_id из "<tg_id>_<подпись>" без проверки.
//
// Нужно, чтобы узнать версию ссылки в базе и уже с ней проверить подпись.
// Сам по себе tg_id секретом не является: без подписи он ничего не даёт.
export function parseSignedUser(raw: string): number | null {
  const separator = raw.lastIndexOf("_");
  if (separator <= 0) return null;
  const tgId = Number(raw.slice(0, separator));
  if (!Number.isFinite(tgId) || tgId <= 0) return null;
  return tgId;
}

// Проверяет параметр user вида "<tg_id>_<подпись>".
export async function verifySignedUser(
  raw: string,
  secret: string,
  version = 0,
): Promise<number | null> {
  const separator = raw.lastIndexOf("_");
  if (separator <= 0) return null;
  const tgId = parseSignedUser(raw);
  if (!tgId) return null;
  const signature = raw.slice(separator + 1);
  if (signature.length !== TOKEN_LENGTH) return null;
  const expected = await subscriptionSignature(tgId, secret, version);
  return constantTimeEqual(signature, expected) ? tgId : null;
}

// Тело подписки для VPN-клиентов (Hiddify, Happ).
export function buildSubscriptionBody(
  links: string[],
  paidUntil: string | null,
): string {
  const meta = [
    "#hide-settings: 1",
    "#profile-title: NAUA Security Mirage",
    "#profile-update-interval: 24",
  ];
  const expiry = paidUntil ? Date.parse(paidUntil) : 0;
  if (Number.isFinite(expiry) && expiry > 0) {
    meta.push(
      `#subscription-userinfo: upload=0; download=0; total=0; expire=${Math.floor(expiry / 1000)}`,
    );
  }
  return [meta.join("\n"), ...links].join("\n");
}

// Base64 для тела подписки (URL-безопасный алфавит).
export function base64Url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}
