import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import {
  base64Url,
  buildSubscriptionBody,
  freeToken,
  parseSignedUser,
  subscriptionSignature,
  verifySignedUser,
} from "./lib/subscription.ts";

// Единая подписка NAUA: платные узлы с двух разных хостеров в одной ссылке.
//
//   Швейцария — панель H1 (inbound из H1_* / FRANCE_*)
//   Беларусь — H1Cloud панель (H1CLOUD_*)
//
// Логику выдачи узлов НЕ дублируем: готовые ссылки запрашиваются у уже
// работающих функций france-config и belarus-config с пользовательским JWT.
// Кросс-импорты между каталогами supabase/functions/* не деплоятся, поэтому
// обращение идёт по HTTP.
//
// Доступ:
//   ?user=<tg_id>_<подпись>  — ссылка для VPN-клиентов (Hiddify/Happ);
//   Authorization: Bearer <jwt> — запрос от приложения.
//
// Ответ: base64-подписка (по умолчанию) либо JSON (?format=json).

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const SUBSCRIPTION_SECRET = Deno.env.get("SUBSCRIPTION_SECRET") || "";

// Названия узлов. Одно место, чтобы в клиенте не было служебных имён
// панелей вроде «mrge_<почта>_<хэш>».
import { h1EnsureNode } from "../_shared/h1panel.ts";

type PaidNodeConfig = {
  id: string;
  label: string;
  configFunction: string;
  sort: number;
};

const DEFAULT_PAID_NODES: PaidNodeConfig[] = [
  { id: "premium_france", label: "🇨🇭 Швейцария (Платная)", configFunction: "france-config", sort: 10 },
  { id: "premium_belarus", label: "🇧🇾 Беларусь (Платная)", configFunction: "belarus-config", sort: 20 },
];

// loadPaidNodes — каталог платных узлов из app_config.
//
// Один и тот же список читают приложение и бот-подписка. Пустой массив —
// осознанное «платных узлов нет»; ошибка или отсутствие строки — запасной
// список Франция/Беларусь, чтобы подписка не опустела из-за сбоя базы.
async function loadPaidNodes(): Promise<PaidNodeConfig[]> {
  try {
    const { data, error } = await service()
      .from("app_config")
      .select("payload")
      .eq("id", "paid_nodes")
      .maybeSingle();
    if (error) {
      console.warn("app_config paid_nodes unavailable, using built-in nodes", {
        error: error.message,
      });
      return DEFAULT_PAID_NODES;
    }
    const payload = (data as { payload?: unknown } | null)?.payload;
    if (!Array.isArray(payload)) return DEFAULT_PAID_NODES;
    if (payload.length === 0) return [];

    const nodes: PaidNodeConfig[] = [];
    for (const raw of payload) {
      if (!raw || typeof raw !== "object") continue;
      const item = raw as Record<string, unknown>;
      if (item.enabled === false) continue;
      const id = String(item.id || "").trim();
      const configFunction = String(item.config_function || "").trim();
      if (!id || !configFunction) continue;
      if (!/^[a-z0-9][a-z0-9-]{1,63}$/.test(configFunction)) continue;
      if (nodes.some((node) => node.id === id)) continue;
      const label = String(item.label_ru || item.label_en || id).trim() || id;
      const sort = Number(item.sort);
      nodes.push({
        id,
        label,
        configFunction,
        sort: Number.isFinite(sort) ? sort : 100,
      });
    }
    if (nodes.length === 0) return DEFAULT_PAID_NODES;
    return nodes.sort((a, b) => a.sort - b.sort || a.id.localeCompare(b.id));
  } catch (error) {
    console.warn("app_config paid_nodes lookup failed, using built-in nodes", {
      error: error instanceof Error ? error.message : error,
    });
    return DEFAULT_PAID_NODES;
  }
}

// withNodeName — заменяет подпись узла (всё после #), не трогая сам конфиг.
function withNodeName(link: string, name: string): string {
  return `${link.split("#")[0]}#${encodeURIComponent(name)}`;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

// Шифрование ссылки для Happ (happ://crypt5/…): у такой ссылки приложение
// не показывает ни адрес подписки, ни конфиги серверов.
//
// Из браузера crypto.happ.su блокируется Cloudflare (запрос с Origin), поэтому
// шифрует сервер: страница подключения зовёт ?op=encrypt&u=<наша ссылка>.
const HAPP_CRYPTO_API = Deno.env.get("HAPP_CRYPTO_API") ||
  "https://crypto.happ.su/api-v2.php";

async function encryptForHapp(target: string): Promise<string | null> {
  try {
    const response = await fetch(HAPP_CRYPTO_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: target }),
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok) return null;
    const parsed = await response.json();
    const link = String(parsed?.encrypted_link || "");
    return link.startsWith("happ://") ? link : null;
  } catch {
    return null;
  }
}

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  vless_key: string | null;
  belarus_vless_key: string | null;
};

type AccountRow = {
  tg_id: number;
  user_id: string;
  email: string | null;
  access_token: string | null;
  refresh_token: string | null;
  access_expires_at: string | null;
  link_version?: number | null;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function service() {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

// linkVersion — версия ссылки подписки.
//
// После сброса в подписи участвует номер версии, поэтому проверять её нужно
// до сверки подписи. Нет аккаунта — версия нулевая (поведение прежнее).
async function linkVersion(tgId: number): Promise<number> {
  const { data, error } = await service()
    .from("telegram_accounts")
    .select("link_version")
    .eq("tg_id", tgId)
    .limit(1);
  if (error) throw new Error(error.message);
  const value = Number((data && data[0]?.link_version) ?? 0);
  return Number.isFinite(value) && value > 0 ? value : 0;
}

// Секрет подписи: из окружения, иначе — JWT-секрет Supabase.
function secret(): string {
  return SUBSCRIPTION_SECRET || Deno.env.get("SUPABASE_JWT_SECRET") || "";
}

// Обновляет истёкший access_token через refresh_token.
async function refreshAccessToken(account: AccountRow): Promise<string | null> {
  if (!account.refresh_token) return account.access_token;
  let response: Response;
  try {
    response = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, {
      method: "POST",
      headers: {
        apikey: SUPABASE_ANON_KEY,
        Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ refresh_token: account.refresh_token }),
      signal: AbortSignal.timeout(8_000),
    });
  } catch {
    // Google/GoTrue молчит — подписка должна собраться на сохранённых ключах.
    return account.access_token;
  }
  if (!response.ok) return account.access_token;
  try {
    const parsed = await response.json();
    const accessToken = String(parsed.access_token || "");
    if (!accessToken) return account.access_token;
    await service().from("telegram_accounts").update({
      access_token: accessToken,
      refresh_token: String(parsed.refresh_token || account.refresh_token || ""),
      access_expires_at: new Date(Date.now() + 50 * 60 * 1000).toISOString(),
      updated_at: new Date().toISOString(),
    }).eq("tg_id", account.tg_id);
    return accessToken;
  } catch {
    return account.access_token;
  }
}

// Запрашивает узел у готовой edge-функции.
async function fetchNode(
  functionName: string,
  bearer: string,
): Promise<{ link: string; uuid: string } | null> {
  try {
    const response = await fetch(
      `${SUPABASE_URL}/functions/v1/${functionName}`,
      {
        headers: {
          apikey: SUPABASE_ANON_KEY,
          Authorization: `Bearer ${bearer}`,
        },
        signal: AbortSignal.timeout(20_000),
      },
    );
    if (!response.ok) {
      console.warn("paid node config failed", { functionName, status: response.status });
      return null;
    }
    const parsed = await response.json();
    const link = String(parsed?.vlessKey || "");
    if (!link.startsWith("vless://")) {
      console.warn("paid node config returned no VLESS link", { functionName });
      return null;
    }
    return { link, uuid: String(parsed?.clientUuid || "") };
  } catch (error) {
    console.warn("paid node config request failed", {
      functionName,
      error: error instanceof Error ? error.message : error,
    });
    return null;
  }
}

// storedNodeLink — уже выданный ключ из subscriptions.
//
// Запасной путь на случай, когда Google-сессия бота отозвана или протухла:
// подписка оплачена, ключи лежат в базе, и клиент не должен оставаться с
// одним бесплатным узлом. Франция и Беларусь хранят ключи в отдельных
// колонках; для остальных узлов ключа в базе нет.
function storedNodeLink(
  nodeId: string,
  subscription: SubscriptionRow | undefined,
): { link: string; uuid: string } | null {
  if (!subscription) return null;
  const link = nodeId === "premium_france"
    ? subscription.vless_key
    : nodeId === "premium_belarus"
    ? subscription.belarus_vless_key
    : null;
  if (!link || !link.startsWith("vless://")) return null;
  const uuid = link.match(/^vless:\/\/([0-9a-fA-F-]{36})@/)?.[1] || "";
  return { link, uuid };
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Настройки Supabase заданы не полностью");
    }

    const url = new URL(req.url);

    // Шифрование ссылки для Happ. Принимаем только свою ссылку подписки
    // с валидной подписью — иначе сервис стал бы открытым шифровальщиком.
    if (url.searchParams.get("op") === "encrypt") {
      const target = url.searchParams.get("u") || "";
      let parsed: URL;
      try {
        parsed = new URL(target);
      } catch {
        return json({ error: "Некорректная ссылка" }, 400);
      }
      if (
        parsed.origin !== new URL(SUPABASE_URL).origin ||
        !parsed.pathname.startsWith("/functions/v1/telegram-subscription")
      ) {
        return json({ error: "Можно шифровать только свою ссылку подписки" }, 400);
      }
      const rawUser = parsed.searchParams.get("user") || "";
      const userTgId = parseSignedUser(rawUser);
      if (!userTgId) {
        return json({ error: "Некорректная подпись подписки" }, 403);
      }
      const verified = await verifySignedUser(
        rawUser,
        secret(),
        await linkVersion(userTgId),
      );
      if (!verified) {
        return json({ error: "Некорректная подпись подписки" }, 403);
      }
      // Бесплатный узел открывается только по отдельной подписи. Страница
      // присылает обычную ссылку, а подпись и адрес с бесплатным узлом
      // остаются на сервере: в браузер уходит только happ://crypt5/… .
      const freeLink = new URL(target);
      freeLink.searchParams.set("free", await freeToken(verified, secret()));
      const encrypted = await encryptForHapp(freeLink.toString());
      if (!encrypted) return json({ error: "Шифрование недоступно" }, 502);
      return json({ encrypted_link: encrypted });
    }

    const rawUser = url.searchParams.get("user") || "";
    const bearerHeader = req.headers.get("Authorization") || "";
    const bearer = bearerHeader.startsWith("Bearer ") ? bearerHeader.slice(7).trim() : "";

    let account: AccountRow | null = null;
    let subjectId: string | null = null;
    let tgId: number | null = null;

    if (rawUser) {
      const userTgId = parseSignedUser(rawUser);
      if (!userTgId) {
        return json({ error: "Некорректная подпись подписки" }, 403);
      }
      // Версию ссылки узнаём до проверки подписи: после сброса в подписи
      // участвует номер версии. Ошибка базы закрывает доступ, а не открывает.
      const { data, error } = await service()
        .from("telegram_accounts")
        .select("tg_id,user_id,email,access_token,refresh_token,access_expires_at,link_version")
        .eq("tg_id", userTgId)
        .limit(1);
      if (error) throw new Error(error.message);
      account = ((data && data[0]) as AccountRow | undefined) ?? null;
      const version = Number(account?.link_version ?? 0) || 0;
      const verified = await verifySignedUser(rawUser, secret(), version);
      if (!verified) {
        return json({ error: "Некорректная подпись подписки" }, 403);
      }
      tgId = verified;
      subjectId = account?.user_id ?? null;
    } else if (bearer) {
      const { data, error } = await service().auth.getUser(bearer);
      if (error || !data?.user?.id) {
        return json({ error: "Сессия недействительна" }, 401);
      }
      subjectId = data.user.id;
      const { data: rows } = await service()
        .from("telegram_accounts")
        .select("tg_id,user_id,email,access_token,refresh_token,access_expires_at")
        .eq("user_id", data.user.id)
        .limit(1);
      account = ((rows && rows[0]) as AccountRow | undefined) ?? null;
      tgId = account?.tg_id ?? null;
    } else {
      return json({ error: "Нужен параметр user или заголовок Authorization" }, 400);
    }

    // Сессия Google могла протухнуть — обновляем.
    let userToken = account?.access_token || "";
    if (account) {
      const expiresAt = account.access_expires_at
        ? Date.parse(account.access_expires_at)
        : 0;
      if (!userToken || (Number.isFinite(expiresAt) && expiresAt - Date.now() < 60_000)) {
        userToken = (await refreshAccessToken(account)) || userToken;
      }
    }

    // Подписка привязана к Google-аккаунту. Без него доступны только
    // бесплатные обходы.
    let subscription: SubscriptionRow | undefined;
    let expiry = 0;
    if (subjectId) {
      const { data: subRows, error: subError } = await service()
        .from("subscriptions")
        .select("user_id,email,is_active,plan,paid_until,vless_key,belarus_vless_key")
        .eq("user_id", subjectId)
        .limit(1);
      if (subError) throw new Error(subError.message);
      subscription = (subRows && subRows[0]) as SubscriptionRow | undefined;
      if (subscription?.is_active && subscription.paid_until) {
        const parsed = Date.parse(subscription.paid_until);
        if (Number.isFinite(parsed)) expiry = parsed;
      }
    }

    const hasPaid = Boolean(subscription?.is_active && expiry > Date.now());
    if (subjectId && !hasPaid && !tgId) {
      return json({ error: "Нет активной подписки" }, 402);
    }

    // Каталог платных узлов серверный: Франция и Беларусь — лишь значения
    // по умолчанию. Новая Финляндия появляется в подписке без пересборки.
    const paidNodeConfigs = await loadPaidNodes();
    const paidNodes = await Promise.all(
      paidNodeConfigs.map(async (node) => ({
        node,
        result: userToken ? await fetchNode(node.configFunction, userToken) : null,
      })),
    );

    const links: string[] = [];
    const nodes: Record<string, unknown>[] = [];
    for (const { node, result } of paidNodes) {
      // Сначала свежий ключ от config-функции; если сессия Google отозвана
      // или панель не ответила — уже выданный ключ из subscriptions.
      let resolved = result ?? storedNodeLink(node.id, subscription);
      // Токен в Happ-ссылке живёт недолго, а ключ мог быть очищен при смене
      // узла. Для Швейцарии бот сам заводит/находит клиента в панели H1,
      // иначе у оплатившего остаются только Беларусь и бесплатный узел.
      if (!resolved && subscription && hasPaid && node.configFunction === "france-config" && subscription.email) {
        try {
          const ensured = await h1EnsureNode(subscription.email, subscription.paid_until);
          resolved = { link: ensured.link, uuid: ensured.uuid };
        } catch (error) {
          console.warn(
            "subscription: H1 fallback failed:",
            error instanceof Error ? error.message : error,
          );
        }
      }
      if (!resolved) continue;
      const label = `NAUA Security Mirage ${node.label}`;
      links.push(withNodeName(resolved.link, label));
      nodes.push({ host: node.id, label, uuid: resolved.uuid });
    }

    // Бесплатные узлы в подписке не выдаются: их парсер Happ всё равно не
    // читает, а приложение получает пул провайдера само. Пришедшему из бота
    // без платной подписки остаётся просьба купить — заголовок и кнопка ниже.
    const body = buildSubscriptionBody(
      links,
      hasPaid ? subscription?.paid_until ?? null : null,
      hasPaid,
    );
    const encoded = base64Url(body);

    // Пустая подписка — нормальный случай для бесплатного пользователя:
    // в теле только заголовок с просьбой купить и кнопка бота.
    // Ошибку отдаём, только если узлы должны были быть.
    if (links.length === 0 && (hasPaid || !tgId)) {
      return json({ error: "Узлы сейчас недоступны. Попробуй позже." }, 502);
    }

    if (url.searchParams.get("format") === "json") {
      return json({
        user_id: subjectId,
        email: subscription?.email ?? null,
        plan: subscription?.plan ?? "free",
        paid_until: subscription?.paid_until ?? null,
        nodes,
        links,
        subscription: body,
      });
    }

    return new Response(encoded, {
      status: 200,
      headers: {
        ...CORS,
        "Content-Type": "text/plain; charset=utf-8",
        "Content-Disposition": 'attachment; filename="NAUA Security Mirage"',
        "profile-title": "NAUA Security Mirage",
        "hide-settings": "1",
        "profile-update-interval": "24",
        "subscription-userinfo":
          `upload=0; download=0; total=0; expire=${expiry > 0 ? Math.floor(expiry / 1000) : 0}`,
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Внутренняя ошибка";
    console.error("telegram-subscription failed:", message);
    return json({ error: message }, 500);
  }
});
