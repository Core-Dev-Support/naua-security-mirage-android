// Подпись ссылки подписки.
//
// Правило продублировано из telegram-subscription: кросс-импорт между
// каталогами supabase/functions/* не деплоится. Одинаковое поведение
// закреплено тестами в обоих каталогах (subscription_test.ts).

export const TOKEN_LENGTH = 32;

async function hmacHex(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret || "mirage-subscription"),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(mac))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

// linkVersion — номер версии ссылки. Ноль — прежняя подпись, поэтому
// после деплоя действующие ссылки продолжают работать. Сброс ссылки
// увеличивает версию: старые адреса перестают проходить проверку.
export async function subscriptionSignature(
  tgId: number,
  secret: string,
  version = 0,
): Promise<string> {
  const payload = version > 0 ? `${tgId}:${version}` : String(tgId);
  return (await hmacHex(payload, secret)).slice(0, TOKEN_LENGTH);
}

// freeToken — отдельная подпись бесплатного узла.
//
// Нельзя ограничиться флагом вроде ?free=1: обычная ссылка подписки лежит
// в адресе страницы подключения, и любой мог бы дописать флаг руками, получив
// конфиг чужого сервера. Поэтому бесплатный узел открывается только по
// ссылке с подписью, которую знает лишь бот (и сервер, который её шифрует).
export async function freeToken(
  tgId: number,
  secret: string,
): Promise<string> {
  return (await hmacHex(`${tgId}:free`, secret)).slice(0, TOKEN_LENGTH);
}

// withFreeNodes — та же ссылка подписки, но с бесплатным узлом.
//
// Она попадает только внутрь зашифрованной ссылки Happ: там конфиги не видны.
// В обычной подписке (Hiddify, v2rayTun) бесплатного узла нет — иначе его
// адрес, UUID и PublicKey увидел бы любой.
export async function withFreeNodes(
  link: string,
  tgId: number,
  secret: string,
): Promise<string> {
  const url = new URL(link);
  url.searchParams.set("free", await freeToken(tgId, secret));
  return url.toString();
}
