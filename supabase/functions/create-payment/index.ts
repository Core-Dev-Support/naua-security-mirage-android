import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { allowRequest, clientIp, tooManyRequests } from "../_shared/security.ts";
import { PLANS as FALLBACK_PLANS, type Plan } from "../_shared/plans.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const CASHERA_API_KEY = Deno.env.get("CASHERA_API_KEY") || "";
const CASHERA_BASE_URL = "https://api.cashera.cash/api/v1";
const BOT_RETURN_URL = "https://t.me/NAUA_Security_Mirage_Bot";

const ALLOWED_METHODS = new Set(["sbp", "card", "mastercard", "crypto", "cryptobot"]);

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

async function loadPlans(): Promise<Plan[]> {
  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) return FALLBACK_PLANS;
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
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

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  const ip = clientIp(req);
  if (!allowRequest(`create-payment:ip:${ip}`, 30, 60_000)) {
    return tooManyRequests(60);
  }

  try {
    if (!CASHERA_API_KEY) {
      throw new Error("CASHERA_API_KEY is not configured");
    }

    const body = await req.json().catch(() => null);
    if (!body || typeof body !== "object") {
      return jsonResponse({ error: "Invalid JSON body" }, 400);
    }

    const rawUserId = String(body.userId ?? body.user_id ?? "").trim();
    const days = Number(body.days);
    const source = body.source === "app" ? "app" : "bot";
    const paymentMethod = typeof body.paymentMethod === "string"
      ? body.paymentMethod.trim().toLowerCase()
      : "";

    if (!rawUserId || rawUserId.length > 128) {
      return jsonResponse({ error: "userId is required" }, 400);
    }
    if (!Number.isInteger(days) || days <= 0) {
      return jsonResponse({ error: "Valid days is required" }, 400);
    }
    if (paymentMethod && !ALLOWED_METHODS.has(paymentMethod)) {
      return jsonResponse({ error: "Unsupported paymentMethod" }, 400);
    }

    const plans = await loadPlans();
    const plan = plans.find((p) => p.days === days);
    if (!plan) {
      return jsonResponse({ error: "Unknown subscription plan" }, 400);
    }

    const amountMinor = Math.round(plan.amountRub * 100);
    const nonce = crypto.randomUUID().replace(/-/g, "").slice(0, 10);
    const externalId = `${rawUserId}:${plan.days}:${nonce}`.slice(0, 255);
    const callbackUrl = `${SUPABASE_URL}/functions/v1/cashera-webhook`;

    const payload: Record<string, unknown> = {
      amount: amountMinor,
      currency: "RUB",
      external_id: externalId,
      description: `Подписка NAUA Security Mirage (${plan.days} дн.)`,
      callback_url: callbackUrl,
      success_url: BOT_RETURN_URL,
      fail_url: BOT_RETURN_URL,
      metadata: {
        user_id: rawUserId,
        days: plan.days,
        source,
      },
    };
    if (paymentMethod) {
      payload.payment_method = paymentMethod;
    }

    const res = await fetch(`${CASHERA_BASE_URL}/integration/transactions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Api-Key": CASHERA_API_KEY,
      },
      body: JSON.stringify(payload),
    });

    const data = await res.json().catch(() => null);
    if (!res.ok || !data?.payment_url) {
      console.error("Cashera create transaction error:", res.status, data);
      return jsonResponse(
        {
          error: data?.message || "Failed to create payment",
          status: res.status,
          details: data,
        },
        res.status >= 400 && res.status < 500 ? res.status : 502,
      );
    }

    return jsonResponse({
      uuid: data.uuid,
      external_id: data.external_id || externalId,
      payment_url: data.payment_url,
      amount_rub: plan.amountRub,
      days: plan.days,
      status: data.status || "pending",
    });
  } catch (err) {
    console.error("create-payment error:", err instanceof Error ? err.message : err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
