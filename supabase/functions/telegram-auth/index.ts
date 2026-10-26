import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { hadSubscriptionBefore, subscriptionRow } from "./lib/trial.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

// Google-вход для Telegram-бота.
//
// Замысел: Telegram-аккаунт привязывается к Google-аккаунту (Supabase Auth).
// Именно этот аккаунт потом используется в приложении NAUA Security Mirage:
// подписка, оплата и платные узлы общие — вход в боте и вход в приложении
// ведут к одной и той же строке subscriptions.
//
// Поток:
//   1) бот просит ?tg_id=<id>&action=start и получает ссылку на вход;
//   2) пользователь открывает ссылку, входит через Google;
//   3) GoTrue возвращает код на redirect_to этой функции;
//   4) функция меняет код на сессию (PKCE) и сохраняет привязку.
//
// Секреты читаются только из окружения. В репозитории запрещено импортировать
// код из других каталогов supabase/functions/*, поэтому модуль самодостаточен.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const BOT_USERNAME = Deno.env.get("BOT_USERNAME") || "NAUA_Security_Mirage_Bot";
// Общий секрет бота и функции. `action=start` принимает tg_id из URL, поэтому
// без него любой мог бы выписать себе ссылку входа для чужого Telegram и
// привязать к ней Google-аккаунт жертвы (login CSRF), а `action=status`
// раскрывал бы email по любому tg_id.
const BOT_SECRET = (Deno.env.get("BOT_INTERNAL_SECRET") || "").trim();

// Куда GoTrue возвращает пользователя после входа.
const AUTH_REDIRECT_URL =
  Deno.env.get("TELEGRAM_AUTH_REDIRECT_URL") ||
  `${SUPABASE_URL}/functions/v1/telegram-auth`;

// Время жизни попытки входа.
const ATTEMPT_TTL_MS = 10 * 60 * 1000;

// Алфавит RFC 7636 (unreserved).
const PKCE_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-bot-secret",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

// Сравнение без раннего выхода: секрет приходит по сети, и по времени ответа
// не должно быть видно, сколько первых символов совпало.
function botAuthorized(req: Request): boolean {
  if (!BOT_SECRET) return false;
  const provided = req.headers.get("x-bot-secret") ?? "";
  if (provided.length !== BOT_SECRET.length) return false;
  let mismatch = 0;
  for (let i = 0; i < BOT_SECRET.length; i++) {
    mismatch |= BOT_SECRET.charCodeAt(i) ^ provided.charCodeAt(i);
  }
  return mismatch === 0;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// Шлюз Supabase Edge Functions принудительно переписывает Content-Type
// ответа на text/plain для всего, кроме application/json. Поэтому HTML-страницу
// подтверждения отдать нельзя — браузер показал бы исходник вместо вёрстки.
//
// Вместо HTML отвечаем 302-редиректом в чат бота, а подробности пользователь
// получает сообщением от самой функции.
function redirectToBot(param: string, status = 302): Response {
  return new Response(null, {
    status,
    headers: {
      Location: `https://t.me/${BOT_USERNAME}?start=${param}`,
      "Cache-Control": "no-store",
    },
  });
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomToken(length: number): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  let out = "";
  for (const byte of bytes) {
    out += PKCE_ALPHABET[byte % PKCE_ALPHABET.length];
  }
  return out;
}

async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(verifier),
  );
  return base64Url(new Uint8Array(digest));
}

function service() {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

// Убирает протухшие попытки входа.
async function dropExpiredAttempts(): Promise<void> {
  const cutoff = new Date(Date.now() - ATTEMPT_TTL_MS).toISOString();
  await service().from("telegram_oauth_attempts").delete().lt("created_at", cutoff);
}

// Кандидаты на обмен кода: сначала точное совпадение по state, затем —
// самые свежие попытки.
//
// Нужно потому, что GoTrue на внешнем провайдере подменяет переданный нами
// state своим UUID и возвращает на redirect_to уже без нашего state. Поэтому
// единственный способ сопоставить код с попыткой — перебрать свежие попытки
// и остановиться на той, чей PKCE-верификатор подошёл.
async function findCandidates(
  state: string | null,
): Promise<{
  state: string;
  tg_id: number;
  code_verifier: string;
  screen_message_id: number | null;
}[]> {
  const db = service();
  const { data, error } = await db
    .from("telegram_oauth_attempts")
    .select("state,tg_id,code_verifier,created_at,screen_message_id")
    .order("created_at", { ascending: false })
    .limit(5);
  if (error) return [];

  const rows = (data ?? []) as {
    state: string;
    tg_id: number;
    code_verifier: string;
    created_at: string;
    screen_message_id: number | null;
  }[];
  const fresh = rows.filter((row) =>
    Date.now() - Date.parse(row.created_at) <= ATTEMPT_TTL_MS
  );

  // Точное совпадение по state — всегда первым.
  if (state) {
    const exact = fresh.find((row) => row.state === state);
    if (exact) {
      return [{
        state: exact.state,
        tg_id: exact.tg_id,
        code_verifier: exact.code_verifier,
        screen_message_id: exact.screen_message_id ?? null,
      }];
    }
  }
  return fresh.map((row) => ({
    state: row.state,
    tg_id: row.tg_id,
    code_verifier: row.code_verifier,
    screen_message_id: row.screen_message_id ?? null,
  }));
}

type ExchangeResult =
  | { ok: true; accessToken: string; refreshToken: string; userId: string; email: string }
  | { ok: false; reason: string };

// Обмен кода авторизации на сессию Supabase (PKCE).
async function exchangeCode(code: string, verifier: string): Promise<ExchangeResult> {
  const response = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=pkce`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ auth_code: code, code_verifier: verifier }),
  });
  const text = await response.text();
  if (!response.ok) {
    let reason = `HTTP ${response.status}`;
    try {
      const parsed = JSON.parse(text);
      reason = parsed.error_description || parsed.msg || parsed.error || reason;
    } catch {
      // тело не JSON — оставляем HTTP-код
    }
    return { ok: false, reason: String(reason).slice(0, 200) };
  }
  try {
    const parsed = JSON.parse(text);
    const accessToken = parsed?.access_token;
    const userId = parsed?.user?.id;
    if (!accessToken || !userId) {
      return { ok: false, reason: "в ответе нет токена или пользователя" };
    }
    return {
      ok: true,
      accessToken: String(accessToken),
      refreshToken: String(parsed.refresh_token || ""),
      userId: String(userId),
      email: String(parsed?.user?.email || ""),
    };
  } catch {
    return { ok: false, reason: "ответ не разобран" };
  }
}

// Сохраняет привязку Telegram <-> Google-аккаунт.
async function linkAccount(
  tgId: number,
  userId: string,
  email: string,
  accessToken: string,
  refreshToken: string,
  screenMessageId: number | null,
): Promise<void> {
  const db = service();
  const now = new Date().toISOString();

  // Один Google-аккаунт не может быть привязан к двум Telegram-аккаунтам.
  // Раньше прежняя привязка молча удалялась. Это позволяло чужой ссылкой
  // входа перевесить Google-аккаунт жертвы на Telegram злоумышленника,
  // поэтому теперь такой вход отклоняется: сначала нужно отвязать аккаунт
  // в своём чате.
  const { data: otherLinks, error: otherError } = await db
    .from("telegram_accounts")
    .select("tg_id")
    .eq("user_id", userId)
    .neq("tg_id", tgId)
    .limit(1);
  if (otherError) throw new Error(`Не удалось проверить привязку: ${otherError.message}`);
  if (otherLinks && otherLinks.length > 0) {
    throw new Error("Google account is already linked to another Telegram account");
  }

  const { error } = await db.from("telegram_accounts").upsert({
    tg_id: tgId,
    user_id: userId,
    email,
    access_token: accessToken,
    refresh_token: refreshToken,
    access_expires_at: new Date(Date.now() + 50 * 60 * 1000).toISOString(),
    linked_at: now,
    screen_message_id: screenMessageId,
    updated_at: now,
  }, { onConflict: "tg_id" });
  if (error) throw new Error(`Не удалось сохранить привязку: ${error.message}`);

  // Пробник: новому пользователю — 1 день бесплатно. И с рефссылкой, и без.
  // Если подписка уже была (план или срок), пробник не выдаём: это
  // возвращающийся пользователь, а не новый.
  const { data: subRows } = await db
    .from("subscriptions")
    .select("user_id,plan,paid_until")
    .eq("user_id", userId)
    .limit(1);
  const previous = subRows?.[0] as
    | { user_id: string; plan: string | null; paid_until: string | null }
    | undefined;

  // На тот же аккаунт в подписке — чтобы бот и приложение видели одно и то же.
  const row = subscriptionRow({
    userId,
    email,
    tgId,
    now,
    hadSubscription: hadSubscriptionBefore(previous),
  });
  await db.from("subscriptions").upsert(row, { onConflict: "user_id" });
}

// ===================== Точка входа =====================

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Настройки Supabase заданы не полностью");
    }

    const url = new URL(req.url);
    const action = url.searchParams.get("action") || "";
    const code = url.searchParams.get("code");
    const state = url.searchParams.get("state");
    const error = url.searchParams.get("error_description") || url.searchParams.get("error");

    // ---- Шаг 1: выдать ссылку на вход -------------------------------------
    if (action === "start") {
      if (!botAuthorized(req)) return json({ error: "forbidden" }, 403);
      const tgId = Number(url.searchParams.get("tg_id"));
      if (!Number.isFinite(tgId) || tgId <= 0) {
        return json({ error: "Некорректный tg_id" }, 400);
      }
      await dropExpiredAttempts();

      const verifier = randomToken(64);
      const challenge = await pkceChallenge(verifier);
      const newState = randomToken(32);

      // id экрана, который бот перепишет после входа: так в чате остаётся
      // одно сообщение (приветствие превращается в подтверждение).
      const screenRaw = Number(url.searchParams.get("screen_msg") || 0);
      const screenMessageId = Number.isFinite(screenRaw) && screenRaw > 0
        ? screenRaw
        : null;

      const { error: insertError } = await service()
        .from("telegram_oauth_attempts")
        .insert({
          state: newState,
          tg_id: tgId,
          code_verifier: verifier,
          screen_message_id: screenMessageId,
          created_at: new Date().toISOString(),
        });
      if (insertError) {
        throw new Error(`Не удалось сохранить попытку входа: ${insertError.message}`);
      }

      const authorize = new URL(`${SUPABASE_URL}/auth/v1/authorize`);
      authorize.searchParams.set("provider", "google");
      authorize.searchParams.set("redirect_to", AUTH_REDIRECT_URL);
      authorize.searchParams.set("state", newState);
      authorize.searchParams.set("flow_type", "pkce");
      authorize.searchParams.set("code_challenge", challenge);
      authorize.searchParams.set("code_challenge_method", "S256");

      return json({
        url: authorize.toString(),
        state: newState,
        expires_in: ATTEMPT_TTL_MS,
      });
    }

    // ---- Шаг 2: статус привязки -------------------------------------------
    if (action === "status") {
      if (!botAuthorized(req)) return json({ error: "forbidden" }, 403);
      const tgId = Number(url.searchParams.get("tg_id"));
      if (!Number.isFinite(tgId) || tgId <= 0) {
        return json({ error: "Некорректный tg_id" }, 400);
      }
      const { data, error: readError } = await service()
        .from("telegram_accounts")
        .select("user_id,email,linked_at")
        .eq("tg_id", tgId)
        .limit(1);
      if (readError) throw new Error(readError.message);
      const row = (data && data[0]) as
        | { user_id: string; email: string | null; linked_at: string }
        | undefined;
      return json({
        linked: Boolean(row),
        user_id: row?.user_id ?? null,
        email: row?.email ?? null,
        linked_at: row?.linked_at ?? null,
      });
    }

    // ---- Шаг 3: обработка возврата от Google ------------------------------
    if (!code && !error) {
      // Google вернул страницу без кода и без ошибки.
      return redirectToBot("link_failed_no_code", 302);
    }
    if (error) {
      // Пользователь не дал доступ (access_denied) или Google отказал.
      const reason = String(error).includes("access_denied") ? "denied" : "exchange";
      return redirectToBot(`link_failed_${reason}`, 302);
    }

    const candidates = await findCandidates(state);
    if (candidates.length === 0) {
      // Попытки входа нет: ссылка старше 10 минут или state не совпал.
      return redirectToBot("link_failed_expired", 302);
    }

    // Пробуем свежие попытки: подходит та, чей PKCE-верификатор совпал.
    type Session = {
      accessToken: string;
      refreshToken: string;
      userId: string;
      email: string;
    };
    let attempt: (typeof candidates)[number] | null = null;
    let session: Session | null = null;
    for (const candidate of candidates) {
      const result = await exchangeCode(String(code), candidate.code_verifier);
      if (result.ok) {
        attempt = candidate;
        session = result;
        break;
      }
    }
    if (!attempt || !session) {
      // Не удаляем попытки других пользователей: этот обработчик публичный, и
      // случайный code от anyone иначе стирал бы свежие попытки входа всех
      // пользователей. Просроченные записи убирает dropExpiredAttempts() при
      // следующем action=start.
      return redirectToBot("link_failed_exchange", 302);
    }

    await service().from("telegram_oauth_attempts").delete().eq("state", attempt.state);

    await linkAccount(
      attempt.tg_id,
      session.userId,
      session.email,
      session.accessToken,
      session.refreshToken,
      attempt.screen_message_id ?? null,
    );

    // В чат ничего не отправляем: бот перепишет исходный пузырь
    // (приветствие) подтверждением и своим главным меню. Так в чате
    // остаётся ровно одно сообщение, а меню после входа — то же самое,
    // что при /start (один источник правды для кнопок).
    return redirectToBot("linked", 302);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Внутренняя ошибка";
    console.error("telegram-auth failed:", message);
    return redirectToBot("link_failed", 302);
  }
});
