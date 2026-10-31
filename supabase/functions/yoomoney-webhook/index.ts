import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { crypto } from "https://deno.land/std@0.168.0/crypto/mod.ts";
import { allowRequest, clientIp, timingSafeEqual } from "../_shared/security.ts";
import {
  PLANS as FALLBACK_PLANS,
  REFERRAL_BONUS_DAYS,
  REFERRAL_MILESTONE_DAYS,
  REFERRAL_MILESTONE_EVERY,
  buildSignString,
  isAcceptedTransfer,
  isAllowedNotificationType,
  isRubleCurrency,
  nextPaidUntil,
  parsePaymentLabel,
  planDaysForPayment,
  type Plan,
} from "./contract.ts";
import { h1EnsureNode } from "../_shared/h1panel.ts";

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

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const YOOMONEY_SECRET = Deno.env.get("YOOMONEY_SECRET") || "";

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
    if (!timingSafeEqual(calculated, sign.toLowerCase())) {
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
    if (!timingSafeEqual(calculated.toLowerCase(), legacy.toLowerCase())) {
      throw new Error("YooMoney sha1_hash mismatch");
    }
    return;
  }

  throw new Error("YooMoney signature is missing");
}

// creditReferralBonus — начисляет пригласившему бонус за первую оплату реферала.
//
// Платёж пришёл -> user_id плательщика -> его telegram id -> кто его пригласил
// -> аккаунт пригласившего. Продлеваем его подписку и узел в панели H1, иначе
// панель отрежет клиента раньше, чем закончится подписка в базе.
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

    // Узел в панели H1 продлеваем тем же сроком.
    try {
      await h1EnsureNode(
        referrer.email ?? refSub?.email ?? "",
        paidUntil,
      );
    } catch (error) {
      console.error(
        "H1 referral renew failed:",
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

  const ip = clientIp(req);
  if (!allowRequest(`yoomoney:ip:${ip}`, 300, 60_000)) {
    return new Response("Too many requests", {
      status: 429,
      headers: { "Retry-After": "60" },
    });
  }
  const declaredLength = Number(req.headers.get("content-length") || "0");
  if (Number.isFinite(declaredLength) && declaredLength > 64 * 1024) {
    return new Response("Payload too large", { status: 413 });
  }

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }

    const formData = await req.formData();
    // Every field is collected, not a fixed list, because the signature is computed over whatever
    // arrived: a parameter that is not in the list would silently break the HMAC.
    const entries: Array<[string, string]> = [];
    let totalChars = 0;
    for (const [key, value] of formData.entries()) {
      const text = String(value);
      totalChars += key.length + text.length;
      if (totalChars > 64 * 1024) {
        return new Response("Payload too large", { status: 413 });
      }
      entries.push([key, text]);
    }
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
    if (operationId.length > 128 || label.length > 128) {
      await recordArrival({ ...arrival, outcome: "field_too_long" });
      return new Response("operation_id or label is too long", { status: 400 });
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
      // 403, а не 500: повторять такое уведомление бессмысленно, а 5xx
      // заставляет YooMoney слать его заново.
      console.warn("YooMoney webhook: signature rejected", err instanceof Error ? err.message : err);
      return new Response("Invalid signature", { status: 403 });
    }

    // Подпись подтверждает, что уведомление от YooMoney и не изменено.
    // Параметра receiver в HTTP-уведомлениях нет, поэтому принадлежность
    // кошелька проверять здесь нечем: она задана в ссылке quickpay и в
    // настройках HTTP-уведомлений кабинета.

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
    // Срок в label выбирает тариф, а сумма обязана совпадать с его ценой.
    const planDays = planDaysForPayment(
      charged,
      field("commission"),
      requestedDays,
      plans,
    );
    if (planDays === null) {
      // Сумма не совпала с тарифом (для новых ссылок) и слишком мала для
      // пропорционального начисления (для старых). Такие платежи не
      // начисляются автоматически: они попадают в arrival-лог, и оплату
      // можно вернуть вручную.
      await recordArrival({
        ...arrival,
        outcome: requestedDays !== null ? "stale_plan" : "invalid_amount",
      });
      return new Response("Invalid amount", { status: 400 });
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

    // Уникальная квитанция операции. Без неё повторная доставка старого
    // уведомления после более новой оплаты снова продлевала бы подписку:
    // проверка «operation_id равен последнему» этого не ловит.
    const { error: claimError } = await supabase
      .from("processed_payments")
      .insert({ operation_id: operationId, user_id: userId });
    if (claimError) {
      if (claimError.code === "23505") {
        await recordArrival({ ...arrival, outcome: "duplicate_ignored" });
        return new Response("OK - already processed", { status: 200 });
      }
      throw new Error(`Payment claim failed: ${claimError.message}`);
    }

    try {
      const paidUntil = nextPaidUntil(existing?.paid_until || null, planDays ?? 30);
      const expiryMs = Date.parse(paidUntil);
      if (!Number.isFinite(expiryMs)) throw new Error("Invalid subscription expiry");

      const node = await h1EnsureNode(email, paidUntil);
      const key = node.link;
      const { error: upsertError } = await supabase
        .from("subscriptions")
        .upsert({
          user_id: userId,
          email,
          is_active: true,
          plan: "premium",
          paid_until: paidUntil,
          vless_key: key,
          client_uuid: node.uuid,
          flow: "",
          operation_id: operationId,
          updated_at: new Date().toISOString(),
        }, { onConflict: "user_id" });
      if (upsertError) throw new Error(`Subscription upsert failed: ${upsertError.message}`);

      await recordArrival({ ...arrival, outcome: "credited" });
      // Бонус пригласившему — после того, как подписка плательщика начислена.
      await creditReferralBonus(userId, operationId);
      return new Response("OK", { status: 200 });
    } catch (error) {
      // Начисление не удалось — снимаем квитанцию, чтобы YooMoney мог
      // повторить доставку и платёж не потерялся.
      await supabase.from("processed_payments").delete().eq("operation_id", operationId);
      throw error;
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "Webhook processing error";

    console.error("YooMoney webhook rejected:", message);
    return new Response("Internal error", { status: 500 });
  }
});
