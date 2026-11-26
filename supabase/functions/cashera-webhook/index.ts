import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { allowRequest, clientIp, timingSafeEqual } from "../_shared/security.ts";
import { h1EnsureNode } from "../_shared/h1panel.ts";
import {
  PLANS as FALLBACK_PLANS,
  REFERRAL_BONUS_DAYS,
  REFERRAL_MILESTONE_DAYS,
  REFERRAL_MILESTONE_EVERY,
  nextPaidUntil,
  parsePaymentLabel,
  type Plan,
} from "../yoomoney-webhook/contract.ts";

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
  tg_id?: number | null;
};

type CasheraTransaction = {
  uuid?: string;
  external_id?: string;
  status?: string;
  type?: string;
  amount?: number;
  gross_amount?: number;
  net_amount?: number;
  fee_amount?: number;
  currency?: string;
  payment_method?: string;
  metadata?: Record<string, unknown> | null;
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const CASHERA_API_KEY = Deno.env.get("CASHERA_API_KEY") || "";
const CASHERA_SECRET = Deno.env.get("CASHERA_SECRET") || "";

async function recordArrival(fields: Record<string, unknown>): Promise<void> {
  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) return;
    const client = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    await client.from("payment_events").insert(fields);
  } catch {
    // ignored on purpose
  }
}

async function loadPaymentPlans(
  supabase: ReturnType<typeof createClient>,
): Promise<Plan[]> {
  try {
    const { data, error } = await supabase
      .from("app_config")
      .select("payload")
      .eq("id", "subscription_plans")
      .maybeSingle();
    if (error || !Array.isArray(data?.payload)) return FALLBACK_PLANS;

    const plans: Plan[] = [];
    for (const raw of data.payload) {
      if (!raw || typeof raw !== "object") continue;
      const item = raw as Record<string, unknown>;
      const amountRub = Number(item.price_rub);
      const days = Number(item.days);
      if (!Number.isInteger(amountRub) || amountRub <= 0) continue;
      if (!Number.isInteger(days) || days <= 0) continue;
      if (plans.some((p) => p.amountRub === amountRub && p.days === days)) continue;
      plans.push({ amountRub, days });
    }
    return plans.length ? plans : FALLBACK_PLANS;
  } catch {
    return FALLBACK_PLANS;
  }
}

async function creditReferralBonus(
  supabase: ReturnType<typeof createClient>,
  payerUserId: string,
  payerOperationId: string,
): Promise<void> {
  const payerPrefix = payerUserId.slice(0, 8);
  try {
    const { data: accRows } = await supabase
      .from("telegram_accounts")
      .select("tg_id")
      .eq("user_id", payerUserId)
      .limit(1);
    const payerTgId = (accRows?.[0] as { tg_id: number } | undefined)?.tg_id;
    if (!payerTgId) return;

    const { data: userRows } = await supabase
      .from("bot_users")
      .select("referred_by")
      .eq("id", payerTgId)
      .limit(1);
    const referrerTgId = (userRows?.[0] as { referred_by: number | null } | undefined)
      ?.referred_by;
    if (!referrerTgId) return;

    const { data: doneRows } = await supabase
      .from("payment_events")
      .select("id")
      .eq("outcome", "referral_bonus")
      .eq("label_prefix", payerPrefix)
      .limit(1);
    if (doneRows?.length) return;

    const { data: refAccRows } = await supabase
      .from("telegram_accounts")
      .select("user_id,email")
      .eq("tg_id", referrerTgId)
      .limit(1);
    const referrer = refAccRows?.[0] as
      | { user_id: string; email: string | null }
      | undefined;
    if (!referrer) return;
    const referrerPrefix = referrer.user_id.slice(0, 8);

    const { data: bonusRows } = await supabase
      .from("payment_events")
      .select("id")
      .eq("outcome", "referral_bonus")
      .eq("bonus_for", referrerPrefix);
    const paidBefore = bonusRows?.length ?? 0;
    const milestone = (paidBefore + 1) % REFERRAL_MILESTONE_EVERY === 0;
    const days = REFERRAL_BONUS_DAYS + (milestone ? REFERRAL_MILESTONE_DAYS : 0);

    const { data: refSubRows } = await supabase
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow")
      .eq("user_id", referrer.user_id)
      .limit(1);
    const refSub = (refSubRows?.[0] as SubscriptionRow | undefined) || null;

    const paidUntil = nextPaidUntil(refSub?.paid_until || null, days);
    if (!Number.isFinite(Date.parse(paidUntil))) return;

    try {
      await h1EnsureNode(referrer.email ?? refSub?.email ?? "", paidUntil);
    } catch (error) {
      console.error(
        "H1 referral renew failed:",
        error instanceof Error ? error.message : error,
      );
    }

    const { error: upsertError } = await supabase.from("subscriptions").upsert({
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
      outcome: "referral_bonus",
      amount: String(days),
      withdraw_amount: String(days),
    });
  } catch (error) {
    console.error(
      "Referral bonus failed:",
      error instanceof Error ? error.message : error,
    );
  }
}

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const ip = clientIp(req);
  if (!allowRequest(`cashera:ip:${ip}`, 300, 60_000)) {
    return new Response("Too many requests", {
      status: 429,
      headers: { "Retry-After": "60" },
    });
  }

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }
    if (!CASHERA_API_KEY || !CASHERA_SECRET) {
      throw new Error("Cashera credentials are not configured");
    }

    const headerKey = (req.headers.get("x-api-key") || "").trim();
    const headerSecret = (req.headers.get("x-secret") || "").trim();

    if (
      !headerKey ||
      !headerSecret ||
      !timingSafeEqual(headerKey, CASHERA_API_KEY) ||
      !timingSafeEqual(headerSecret, CASHERA_SECRET)
    ) {
      console.warn("Cashera webhook: unauthorized request");
      return new Response("Unauthorized", { status: 401 });
    }

    const body = await req.json().catch(() => null);
    if (!body || typeof body !== "object") {
      return new Response("Invalid JSON", { status: 400 });
    }

    const event = String(body.event ?? "");
    if (event !== "transaction.status_updated") {
      return new Response("OK - event ignored", { status: 200 });
    }

    const tx = (body.transaction || {}) as CasheraTransaction;
    const txUuid = String(tx.uuid || "").trim();
    const externalId = String(tx.external_id || "").trim();
    const status = String(tx.status || "").trim().toLowerCase();
    const txType = String(tx.type || "").trim().toLowerCase();
    const currency = String(tx.currency || "").trim().toUpperCase();
    const amountMinor = Number(tx.amount);
    const netMinor = Number(tx.net_amount ?? tx.amount ?? 0);
    const method = String(tx.payment_method || "cashera").trim().toLowerCase();

    if (!txUuid) {
      return new Response("transaction.uuid is required", { status: 400 });
    }

    const meta = (tx.metadata && typeof tx.metadata === "object") ? tx.metadata : {};
    const parsedFromExternal = parsePaymentLabel(externalId);
    const rawUserId = String(meta.user_id ?? parsedFromExternal.userId ?? "").trim();
    const requestedDaysRaw = Number(meta.days ?? parsedFromExternal.days);
    const requestedDays = Number.isInteger(requestedDaysRaw) && requestedDaysRaw > 0
      ? requestedDaysRaw
      : null;

    const operationId = `cashera:${txUuid}`;
    const arrival = {
      notification_type: `cashera:${method}`,
      operation_id: operationId,
      amount: Number.isFinite(netMinor) ? String(netMinor / 100) : null,
      withdraw_amount: Number.isFinite(amountMinor) ? String(amountMinor / 100) : null,
      commission: Number.isFinite(Number(tx.fee_amount)) ? String(Number(tx.fee_amount) / 100) : null,
      codepro: status,
      label_prefix: rawUserId ? rawUserId.slice(0, 8) : null,
      signature_present: true,
    };

    if (txType !== "deposit" || currency !== "RUB") {
      await recordArrival({ ...arrival, outcome: "unsupported_type_or_currency" });
      return new Response("OK - ignored", { status: 200 });
    }

    if (status !== "paid") {
      await recordArrival({ ...arrival, outcome: `status_${status || "unknown"}` });
      return new Response("OK", { status: 200 });
    }

    if (!rawUserId || !Number.isFinite(amountMinor) || amountMinor <= 0) {
      await recordArrival({ ...arrival, outcome: "missing_user_or_amount" });
      return new Response("Invalid transaction payload", { status: 400 });
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const plans = await loadPaymentPlans(supabase);
    const matchedPlan = plans.find((plan) => {
      if (requestedDays !== null && plan.days !== requestedDays) return false;
      return Math.round(plan.amountRub * 100) === Math.round(amountMinor);
    });

    if (!matchedPlan) {
      await recordArrival({ ...arrival, outcome: "invalid_amount" });
      return new Response("Amount does not match plan", { status: 400 });
    }

    let userId = rawUserId;
    let email = "";
    let targetTgId: number | null = null;
    const isTgPayer = userId.startsWith("tg_") || /^\d{5,15}$/.test(userId);

    if (isTgPayer) {
      targetTgId = Number(userId.replace(/^tg_/, ""));
      const { data: tgAcc } = await supabase
        .from("telegram_accounts")
        .select("user_id,email")
        .eq("tg_id", targetTgId)
        .limit(1);
      if (tgAcc && tgAcc[0]) {
        userId = tgAcc[0].user_id;
        email = tgAcc[0].email || `tg_${targetTgId}@mirage.internal`;
      } else {
        userId = `tg_${targetTgId}`;
        email = `tg_${targetTgId}@mirage.internal`;
      }
    } else {
      const { data: userData, error: userError } = await supabase.auth.admin.getUserById(userId);
      if (userError || !userData.user?.email) {
        await recordArrival({ ...arrival, outcome: "unknown_payer" });
        return new Response("Unknown payer account", { status: 400 });
      }
      email = userData.user.email;
    }

    const { data: existingRows, error: existingError } = await supabase
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow,operation_id,tg_id")
      .eq("user_id", userId)
      .limit(1);
    if (existingError) throw new Error(`Subscription lookup failed: ${existingError.message}`);

    const existing = (existingRows?.[0] as SubscriptionRow | undefined) || null;
    if (existing?.operation_id === operationId) {
      await recordArrival({ ...arrival, outcome: "duplicate_ignored" });
      return new Response("OK - already processed", { status: 200 });
    }

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
      const paidUntil = nextPaidUntil(existing?.paid_until || null, matchedPlan.days);
      if (!Number.isFinite(Date.parse(paidUntil))) {
        throw new Error("Invalid subscription expiry");
      }

      const node = await h1EnsureNode(email, paidUntil);
      const { error: upsertError } = await supabase
        .from("subscriptions")
        .upsert({
          user_id: userId,
          email,
          tg_id: targetTgId ?? existing?.tg_id ?? null,
          is_active: true,
          plan: "premium",
          paid_until: paidUntil,
          vless_key: node.link,
          client_uuid: node.uuid,
          flow: "",
          operation_id: operationId,
          updated_at: new Date().toISOString(),
        }, { onConflict: "user_id" });
      if (upsertError) throw new Error(`Subscription upsert failed: ${upsertError.message}`);

      await recordArrival({ ...arrival, outcome: "credited" });
      await creditReferralBonus(supabase, userId, operationId);
      return new Response("OK", { status: 200 });
    } catch (error) {
      await supabase.from("processed_payments").delete().eq("operation_id", operationId);
      throw error;
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "Webhook processing error";
    console.error("Cashera webhook error:", message);
    return new Response("Internal error", { status: 500 });
  }
});
