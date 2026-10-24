import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { crypto } from "https://deno.land/std@0.168.0/crypto/mod.ts";
import {
  PLANS as FALLBACK_PLANS,
  REFERRAL_BONUS_DAYS,
  REFERRAL_MILESTONE_DAYS,
  REFERRAL_MILESTONE_EVERY,
  buildSignString,
  isAcceptedTransfer,
  isAllowedNotificationType,
  isExpectedPaymentAmount,
  isRubleCurrency,
  makeVlessUrl,
  nextPaidUntil,
  parsePaymentLabel,
  planDaysForPayment,
  type Plan,
} from "./contract.ts";

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  vless_key: string | null;
  client_uuid: string | null;
  flow: string | null;
  operation_id: string | null;
};

type ThreeXClient = {
  id: string;
  email?: string;
  flow?: string;
  enable?: boolean;
  expiryTime?: number;
};

const THREE_X_UI_BASE_URL = Deno.env.get("THREE_X_UI_BASE_URL") || "";
const THREE_X_UI_USER = Deno.env.get("THREE_X_UI_USERNAME") || Deno.env.get("THREE_X_UI_USER") || "";
const THREE_X_UI_PASS = Deno.env.get("THREE_X_UI_PASSWORD") || Deno.env.get("THREE_X_UI_PASS") || "";
const THREE_X_UI_INBOUND_ID = Number(Deno.env.get("THREE_X_UI_INBOUND_ID") || "2");
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const YOOMONEY_SECRET = Deno.env.get("YOOMONEY_SECRET") || "";
const FRANCE_HOST = Deno.env.get("FRANCE_HOST") || "";
const FRANCE_PORT = Deno.env.get("FRANCE_PORT") || "443";
const FRANCE_PBK = Deno.env.get("FRANCE_PBK") || "";
const FRANCE_SNI = Deno.env.get("FRANCE_SNI") || "";
const FRANCE_SID = Deno.env.get("FRANCE_SID") || "";
const FRANCE_SPX = Deno.env.get("FRANCE_SPX") || "/";
const FRANCE_FINGERPRINT = Deno.env.get("FRANCE_FINGERPRINT") || "chrome";
const YOOMONEY_EXPECTED_AMOUNT_RUB = Deno.env.get("YOOMONEY_EXPECTED_AMOUNT_RUB") || "30";
const YOOMONEY_AMOUNT_TOLERANCE_KOPECKS = Number(
  Deno.env.get("YOOMONEY_AMOUNT_TOLERANCE_KOPECKS") || "200",
);

const DEFAULT_CLIENT_FLOW = Deno.env.get("THREE_X_UI_DEFAULT_FLOW") || "";

function assert3xSuccess(response: Response, body: string, operation: string, allowEmpty = false): void {
  if (!response.ok) {
    throw new Error(`${operation} failed with HTTP ${response.status}`);
  }
  if (!body) {
    if (allowEmpty) return;
    throw new Error(`${operation} returned an empty response`);
  }
  try {
    const parsed = JSON.parse(body);
    if (!parsed || parsed.success !== true) {
      throw new Error(`${operation} was rejected by 3X-UI`);
    }
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`${operation} returned invalid JSON`);
    }
    throw error;
  }
}

// Verifies the "sign" parameter: HMAC-SHA256, lowercase hex, over the alphabetically ordered
// percent-encoded parameters other than "sign" itself, keyed with the secret from the merchant's
// HTTP notification settings.
//
// This replaced a SHA-1 check of sha1_hash. YooMoney stopped sending sha1_hash on 18 May 2026 and
// documents "sign" as the replacement, so every payment since then was refused with no way to verify
// it. sha1_hash is still accepted when it is the only signature present, for notifications that were
// already in flight at the time of the change.
async function verifyYooMoneySign(entries: Array<[string, string]>): Promise<void> {
  if (!YOOMONEY_SECRET) {
    throw new Error("YOOMONEY_SECRET is not configured");
  }
  const received = new Map(entries);
  const sign = (received.get("sign") ?? "").trim();
  const legacy = (received.get("sha1_hash") ?? "").trim();

  if (sign) {
    const signString = buildSignString(entries);
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(YOOMONEY_SECRET),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signString));
    const calculated = Array.from(new Uint8Array(mac))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");
    if (calculated !== sign.toLowerCase()) {
      throw new Error("YooMoney sign mismatch");
    }
    return;
  }

  if (legacy) {
    const notificationType = received.get("notification_type") ?? "";
    const operationId = received.get("operation_id") ?? "";
    const amount = received.get("amount") ?? "";
    const currency = received.get("currency") ?? "";
    const datetime = received.get("datetime") ?? "";
    const sender = received.get("sender") ?? "";
    const codepro = received.get("codepro") ?? "";
    const label = received.get("label") ?? "";
    const checkString =
      `${notificationType}&${operationId}&${amount}&${currency}&${datetime}&${sender}&${codepro}&${YOOMONEY_SECRET}&${label}`;
    const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(checkString));
    const calculated = Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");
    if (calculated.toLowerCase() !== legacy.toLowerCase()) {
      throw new Error("YooMoney sha1_hash mismatch");
    }
    return;
  }

  throw new Error("YooMoney signature is missing");
}

function vlessUrl(uuid: string, flow: string): string {
  return makeVlessUrl(
    uuid,
    flow,
    FRANCE_HOST,
    FRANCE_PORT,
    FRANCE_PBK,
    FRANCE_FINGERPRINT,
    FRANCE_SNI,
    FRANCE_SID,
    FRANCE_SPX,
  );
}

async function getInboundClient(cookie: string, userId: string, email: string, clientUuid: string | null): Promise<ThreeXClient | null> {
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/get/${THREE_X_UI_INBOUND_ID}`, {
    headers: { Cookie: cookie },
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI inbound lookup");
  const root = JSON.parse(body);
  const rawSettings = root.obj?.settings;
  const settings = typeof rawSettings === "string"
    ? JSON.parse(rawSettings)
    : (rawSettings || {});
  const clients: ThreeXClient[] = Array.isArray(settings.clients) ? settings.clients : [];
  const now = Date.now();
  const matching = clients.filter((client) => {
    const id = (client.id || "").toLowerCase();
    const clientEmail = (client.email || "").toLowerCase();
    const matches = (clientUuid && id === clientUuid.toLowerCase()) ||
      (clientEmail && clientEmail === email.toLowerCase()) ||
      clientEmail === `user_${userId.substring(0, 8).toLowerCase()}@mirage.app`;
    const expiry = Number(client.expiryTime || 0);
    return matches && client.enable !== false && (expiry === 0 || expiry > now);
  });
  matching.sort((left, right) => {
    const leftExact = Boolean(clientUuid && (left.id || "").toLowerCase() === clientUuid.toLowerCase());
    const rightExact = Boolean(clientUuid && (right.id || "").toLowerCase() === clientUuid.toLowerCase());
    if (leftExact !== rightExact) return leftExact ? -1 : 1;
    return Number(right.expiryTime || 0) - Number(left.expiryTime || 0);
  });
  return matching[0] || null;
}

type InboundObj = Record<string, unknown> & { settings?: unknown };

async function readInboundObj(cookie: string): Promise<InboundObj> {
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/get/${THREE_X_UI_INBOUND_ID}`, {
    headers: { Cookie: cookie },
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI inbound lookup");
  const obj = JSON.parse(body)?.obj;
  if (!obj || typeof obj !== "object") throw new Error("3X-UI inbound response has no object");
  return obj as InboundObj;
}

function parseSettings(raw: unknown): Record<string, unknown> {
  if (typeof raw === "string") {
    try {
      return JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return {};
    }
  }
  return raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
}

async function renew3xClient(cookie: string, uuid: string, expiryMs: number): Promise<ThreeXClient> {
  const obj = await readInboundObj(cookie);
  const settings = parseSettings(obj.settings);
  const clients: ThreeXClient[] = Array.isArray(settings.clients) ? settings.clients : [];
  const target = clients.find((client) => (client.id || "").toLowerCase() === uuid.toLowerCase());
  if (!target) throw new Error("3X-UI client is no longer present in the inbound");
  target.expiryTime = expiryMs;
  target.enable = true;

  target.flow = "";
  settings.clients = clients;
  obj.settings = JSON.stringify(settings);

  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/update/${THREE_X_UI_INBOUND_ID}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Cookie: cookie },
    body: JSON.stringify(obj),
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI inbound update");

  const verifiedSettings = parseSettings((await readInboundObj(cookie)).settings);
  const verified = (Array.isArray(verifiedSettings.clients) ? verifiedSettings.clients : [])
    .find((client) => (client.id || "").toLowerCase() === uuid.toLowerCase());
  if (!verified || Number(verified.expiryTime || 0) < expiryMs) {
    throw new Error("3X-UI did not apply the new client expiry");
  }
  return verified;
}

async function loginTo3xUi(): Promise<string> {
  if (!THREE_X_UI_BASE_URL || !THREE_X_UI_USER || !THREE_X_UI_PASS) {
    throw new Error("3X-UI configuration is incomplete");
  }
  const body = new URLSearchParams({ username: THREE_X_UI_USER, password: THREE_X_UI_PASS });
  const response = await fetch(`${THREE_X_UI_BASE_URL}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const responseBody = await response.text();
  assert3xSuccess(response, responseBody, "3X-UI login");
  const cookie = response.headers.get("set-cookie")?.split(";")[0] || "";
  if (!cookie) throw new Error("3X-UI login did not return a session cookie");
  return cookie;
}

async function ensure3xClient(
  cookie: string,
  userId: string,
  email: string,
  existing: SubscriptionRow | null,
  expiryMs: number,
): Promise<{ uuid: string; flow: string }> {
  const existingClient = await getInboundClient(
    cookie,
    userId,
    email,
    existing?.client_uuid || null,
  );
  if (existingClient) {

    const renewed = await renew3xClient(cookie, existingClient.id, expiryMs);
    return { uuid: renewed.id, flow: "" };
  }

  const uuid = crypto.randomUUID();
  const flow = DEFAULT_CLIENT_FLOW;
  const client = {
    id: uuid,

    email: `user_${userId.substring(0, 8).toLowerCase()}@mirage.app`,
    flow,
    limitIp: 0,
    totalGB: 0,
    expiryTime: expiryMs,
    enable: true,
    tgId: "",
    subId: uuid.replace(/-/g, "").slice(0, 16),
  };
  const settings = JSON.stringify({ clients: [client] });
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/addClient`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Cookie: cookie },
    body: JSON.stringify({ id: THREE_X_UI_INBOUND_ID, settings }),
  });
  const body = await response.text();

  assert3xSuccess(response, body, "3X-UI addClient", true);
  const verified = await getInboundClient(cookie, userId, email, uuid);
  const verifiedExpiry = Number(verified?.expiryTime || 0);
  if (!verified || verified.id.toLowerCase() !== uuid.toLowerCase() || (verifiedExpiry !== 0 && verifiedExpiry < expiryMs)) {
    throw new Error("3X-UI addClient was not verified in the inbound");
  }
  return { uuid: verified.id, flow: verified.flow ?? flow };
}

// creditReferralBonus — начисляет пригласившему бонус за первую оплату реферала.
//
// Платёж пришёл -> user_id плательщика -> его telegram id -> кто его пригласил
// -> аккаунт пригласившего. Продлеваем его подписку и узел в 3X-UI, иначе панель
// отрежет клиента раньше, чем закончится подписка в базе.
async function creditReferralBonus(
  payerUserId: string,
  payerOperationId: string,
): Promise<void> {
  const payerPrefix = payerUserId.slice(0, 8);
  try {
    const client = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: accRows } = await client
      .from("telegram_accounts")
      .select("tg_id")
      .eq("user_id", payerUserId)
      .limit(1);
    const payerTgId = (accRows?.[0] as { tg_id: number } | undefined)?.tg_id;
    if (!payerTgId) return;

    const { data: userRows } = await client
      .from("bot_users")
      .select("referred_by")
      .eq("id", payerTgId)
      .limit(1);
    const referrerTgId = (userRows?.[0] as { referred_by: number | null } | undefined)
      ?.referred_by;
    if (!referrerTgId) return;

    // Бонус за этого плательщика уже начисляли?
    const { data: doneRows } = await client
      .from("payment_events")
      .select("id")
      .eq("outcome", "referral_bonus")
      .eq("label_prefix", payerPrefix)
      .limit(1);
    if (doneRows?.length) return;

    const { data: refAccRows } = await client
      .from("telegram_accounts")
      .select("user_id,email")
      .eq("tg_id", referrerTgId)
      .limit(1);
    const referrer = refAccRows?.[0] as
      | { user_id: string; email: string | null }
      | undefined;
    if (!referrer) return;
    const referrerPrefix = referrer.user_id.slice(0, 8);

    // Каждые пять оплативших рефералов — дополнительные десять дней.
    const { data: bonusRows } = await client
      .from("payment_events")
      .select("id")
      .eq("outcome", "referral_bonus")
      .eq("bonus_for", referrerPrefix);
    const paidBefore = bonusRows?.length ?? 0;
    const milestone = (paidBefore + 1) % REFERRAL_MILESTONE_EVERY === 0;
    const days = REFERRAL_BONUS_DAYS +
      (milestone ? REFERRAL_MILESTONE_DAYS : 0);

    const { data: refSubRows } = await client
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow")
      .eq("user_id", referrer.user_id)
      .limit(1);
    const refSub = (refSubRows?.[0] as SubscriptionRow | undefined) || null;

    const paidUntil = nextPaidUntil(refSub?.paid_until || null, days);
    const expiryMs = Date.parse(paidUntil);
    if (!Number.isFinite(expiryMs)) return;

    // Узел в 3X-UI продлеваем тем же сроком.
    try {
      const cookie = await loginTo3xUi();
      await ensure3xClient(
        cookie,
        referrer.user_id,
        referrer.email ?? refSub?.email ?? "",
        refSub,
        expiryMs,
      );
    } catch (error) {
      console.error(
        "3X-UI referral renew failed:",
        error instanceof Error ? error.message : error,
      );
    }

    const { error: upsertError } = await client.from("subscriptions").upsert({
      user_id: referrer.user_id,
      email: referrer.email ?? refSub?.email ?? "",
      tg_id: referrerTgId,
      is_active: true,
      plan: refSub?.plan || "premium",
      paid_until: paidUntil,
      updated_at: new Date().toISOString(),
    }, { onConflict: "user_id" });
    if (upsertError) {
      throw new Error(`Referral bonus upsert failed: ${upsertError.message}`);
    }

    await recordArrival({
      notification_type: "referral_bonus",
      operation_id: `referral:${payerOperationId}`,
      label_prefix: payerPrefix,
      bonus_for: referrerPrefix,
      // Именно referral_bonus: по этому значению и защита от повторного
      // начисления, и подсчёт заработанных дней в профиле.
      outcome: "referral_bonus",
      amount: String(days),
      withdraw_amount: String(days),
    });
  } catch (error) {
    // Ошибка бонуса не должна отменять начисление самой подписки.
    console.error(
      "Referral bonus failed:",
      error instanceof Error ? error.message : error,
    );
  }
}

// Records that a notification arrived and which check stopped it. The platform log API returns
// nothing for this project and the merchant panel shows an empty notification history, so without
// this there is no way to tell a payment that never arrived from one that was rejected. The table
// has row level security on with no policy, so the anon key inside the APK cannot read it.
//
// Failures here are swallowed: this is a diagnostic, and a webhook that fails because its own
// bookkeeping failed would turn a paid customer into an unpaid one.
// loadPaymentPlans — тарифы из app_config.
//
// Цена и срок живут в одной таблице, поэтому изменение цены в Supabase
// подхватывают и приложение, и бот, и этот вебхук без пересборки APK.
// Если таблица недоступна, остаётся встроенный список: принять оплату
// важнее, чем показать новую цену.
async function loadPaymentPlans(
  load: () => Promise<{
    data: { payload?: unknown } | null;
    error: { message: string } | null;
  }>,
): Promise<Plan[]> {
  try {
    const { data, error } = await load();
    if (error) {
      console.warn("app_config plans unavailable, using built-in plans", { error: error.message });
      return FALLBACK_PLANS;
    }
    const payload = data?.payload;
    if (!Array.isArray(payload)) return FALLBACK_PLANS;

    const plans: Plan[] = [];
    for (const raw of payload) {
      if (!raw || typeof raw !== "object") continue;
      const item = raw as Record<string, unknown>;
      const amountRub = Number(item.price_rub);
      const days = Number(item.days);
      if (!Number.isInteger(amountRub) || amountRub <= 0) continue;
      if (!Number.isInteger(days) || days <= 0) continue;
      if (plans.some((plan) => plan.amountRub === amountRub && plan.days === days)) continue;
      plans.push({ amountRub, days });
    }
    if (!plans.length) return FALLBACK_PLANS;
    return plans.sort((a, b) => a.amountRub - b.amountRub || a.days - b.days);
  } catch (error) {
    console.warn("app_config plans lookup failed, using built-in plans", {
      error: error instanceof Error ? error.message : error,
    });
    return FALLBACK_PLANS;
  }
}

async function recordArrival(fields: Record<string, unknown>): Promise<void> {
  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) return;
    const client = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
    await client.from("payment_events").insert(fields);
  } catch {
    // ignored on purpose
  }
}

serve(async (req) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }

    const formData = await req.formData();
    // Every field is collected, not a fixed list, because the signature is computed over whatever
    // arrived: a parameter that is not in the list would silently break the HMAC.
    const entries: Array<[string, string]> = [];
    for (const [key, value] of formData.entries()) entries.push([key, String(value)]);
    const field = (name: string) => new Map(entries).get(name) ?? "";

    const notificationType = field("notification_type");
    const operationId = field("operation_id");
    const amount = field("amount");
    const withdrawAmount = field("withdraw_amount");
    const currency = field("currency");
    const codepro = field("codepro");
    const unaccepted = field("unaccepted");
    const label = field("label");

    // Only a short prefix of the payer identifier is kept, enough to correlate a notification with a
    // subscription row without turning this table into a list of user identifiers.
    const arrival = {
      notification_type: notificationType,
      operation_id: operationId,
      amount,
      withdraw_amount: withdrawAmount,
      commission: field("commission"),
      codepro,
      label_prefix: label ? label.slice(0, 8) : null,
      signature_present: Boolean(field("sign") || field("sha1_hash")),
    };

    if (!operationId || !label) {
      await recordArrival({ ...arrival, outcome: "missing_operation_id_or_label" });
      return new Response("operation_id and label are required", { status: 400 });
    }
    if (!isAllowedNotificationType(notificationType)) {
      await recordArrival({ ...arrival, outcome: "unsupported_notification_type" });
      return new Response("Unsupported notification type", { status: 400 });
    }
    // codepro is no longer a gate. YooMoney documents it as always false, because transfers with a
    // protection code no longer exist, so requiring "true" refused every settled payment. The
    // decision is left to the signature check below, which is unconditional: an unsigned request
    // still cannot credit anyone, which is what makes dropping this safe.
    if (codepro.toLowerCase() !== "true") {
      console.warn("codepro is not true, continuing to the signature check", { operationId });
    }
    // A transfer flagged unaccepted is held rather than settled and must not be credited.
    if (!isAcceptedTransfer(unaccepted || null)) {
      await recordArrival({ ...arrival, outcome: "transfer_held" });
      return new Response("Transfer is held", { status: 400 });
    }
    // withdraw_amount is what the payer was charged; amount is what landed after YooMoney's cut. The
    // charged figure is the one that must match the price, so it is preferred when present.
    const charged = withdrawAmount || amount;

    // Сумму проверяем после подписи: тарифы читаются из app_config, а без
    // проверенного уведомления нельзя даже записывать попытку в журнал.
    if (!isRubleCurrency(currency)) {
      await recordArrival({ ...arrival, outcome: "unsupported_currency" });
      return new Response("Unsupported currency", { status: 400 });
    }

    try {
      await verifyYooMoneySign(entries);
    } catch (err) {
      await recordArrival({ ...arrival, outcome: "signature_rejected" });
      throw err;
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
    const plans = await loadPaymentPlans(async () => {
      const { data, error } = await supabase
        .from("app_config")
        .select("payload")
        .eq("id", "subscription_plans")
        .maybeSingle();
      return {
        data: (data as { payload?: unknown } | null) ?? null,
        error,
      };
    });
    const { userId, days: requestedDays } = parsePaymentLabel(label);
    if (!userId) {
      await recordArrival({ ...arrival, outcome: "missing_operation_id_or_label" });
      return new Response("operation_id and label are required", { status: 400 });
    }
    // Новые ссылки несут срок в label, поэтому смена цены не требует нового
    // APK. Старые ссылки без срока по-прежнему сопоставляются по сумме.
    const planDays = planDaysForPayment(
      charged,
      field("commission"),
      requestedDays,
      plans,
    );
    if (planDays === null) {
      // Новая ссылка всегда несёт срок. Если цена изменилась между открытием
      // страницы и оплатой, сумма и срок перестанут совпадать: такую оплату
      // лучше отклонить, чем начислить меньше оплаченного.
      if (requestedDays !== null) {
        await recordArrival({ ...arrival, outcome: "stale_plan" });
        return new Response("Plan changed, please pay again", { status: 400 });
      }
      // Старые ссылки без срока принимаем по прежнему правилу (ожидаемая
      // сумма с допуском) — как это было до появления тарифов.
      if (!isExpectedPaymentAmount(charged, field("commission"), YOOMONEY_EXPECTED_AMOUNT_RUB, YOOMONEY_AMOUNT_TOLERANCE_KOPECKS)) {
        await recordArrival({ ...arrival, outcome: "invalid_amount" });
        return new Response("Invalid amount", { status: 400 });
      }
    }

    const { data: userData, error: userError } = await supabase.auth.admin.getUserById(userId);
    if (userError || !userData.user?.email) {
      await recordArrival({ ...arrival, outcome: "unknown_payer" });
      return new Response("Unknown payer account", { status: 400 });
    }
    const email = userData.user.email;

    const { data: existingRows, error: existingError } = await supabase
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow,operation_id")
      .eq("user_id", userId)
      .limit(1);
    if (existingError) throw new Error(`Subscription lookup failed: ${existingError.message}`);

    const existing = (existingRows?.[0] as SubscriptionRow | undefined) || null;
    if (existing?.operation_id === operationId) {
      await recordArrival({ ...arrival, outcome: "duplicate_ignored" });
      return new Response("OK - already processed", { status: 200 });
    }

    const paidUntil = nextPaidUntil(existing?.paid_until || null, planDays ?? 30);
    const expiryMs = Date.parse(paidUntil);
    if (!Number.isFinite(expiryMs)) throw new Error("Invalid subscription expiry");

    const cookie = await loginTo3xUi();
    const client = await ensure3xClient(cookie, userId, email, existing, expiryMs);
    const key = vlessUrl(client.uuid, client.flow);
    const { error: upsertError } = await supabase
      .from("subscriptions")
      .upsert({
        user_id: userId,
        email,
        is_active: true,
        plan: "premium",
        paid_until: paidUntil,
        vless_key: key,
        client_uuid: client.uuid,
        flow: client.flow,
        operation_id: operationId,
        updated_at: new Date().toISOString(),
      }, { onConflict: "user_id" });
    if (upsertError) throw new Error(`Subscription upsert failed: ${upsertError.message}`);

    await recordArrival({ ...arrival, outcome: "credited" });
    // Бонус пригласившему — после того, как подписка плательщика начислена.
    await creditReferralBonus(userId, operationId);
    return new Response("OK", { status: 200 });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Webhook processing error";

    console.error("YooMoney webhook rejected:", message);
    return new Response("Internal error", { status: 500 });
  }
});
