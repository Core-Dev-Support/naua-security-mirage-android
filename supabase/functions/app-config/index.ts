import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

// Public, cacheable configuration for the app and the bot.
//
// The table is writable only with the service role. This function returns a
// sanitized projection: prices, labels and edge-function names. It never
// returns provider URLs, tokens or panel credentials.
//
// Deploy with --no-verify-jwt: the app requests it before sign-in and the bot
// reads it while rendering the payment keyboard.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Cache-Control": "public, max-age=60",
  "Content-Type": "application/json",
};

const PLAN_IDS = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const NODE_IDS = /^[a-z0-9][a-z0-9_]{1,47}$/;
const FUNCTION_NAMES = /^[a-z0-9][a-z0-9-]{1,63}$/;
const FLAGS = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const NODE_KINDS = new Set(["france", "belarus", "vless"]);

type Plan = {
  id: string;
  days: number;
  price_rub: number;
  badge: string | null;
  label_ru: string;
  label_en: string;
};

type PaidNode = {
  id: string;
  kind: "france" | "belarus" | "vless";
  label_ru: string;
  label_en: string;
  flag: string;
  config_function: string;
  sort: number;
};

type ConfigRow = {
  id: string;
  payload: unknown;
  updated_at: string | null;
};

const DEFAULT_PLANS: Plan[] = [
  { id: "1m", days: 30, price_rub: 40, badge: null, label_ru: "30 дней", label_en: "30 days" },
  { id: "3m", days: 90, price_rub: 110, badge: "-8%", label_ru: "3 месяца", label_en: "3 months" },
  { id: "6m", days: 180, price_rub: 200, badge: "-17%", label_ru: "6 месяцев", label_en: "6 months" },
  { id: "12m", days: 365, price_rub: 330, badge: "-32%", label_ru: "1 год", label_en: "1 year" },
];

const DEFAULT_NODES: PaidNode[] = [
  {
    id: "premium_france",
    kind: "france",
    label_ru: "Франция (Платная)",
    label_en: "France (Paid)",
    flag: "france",
    config_function: "france-config",
    sort: 10,
  },
  {
    id: "premium_belarus",
    kind: "belarus",
    label_ru: "Беларусь (Платная)",
    label_en: "Belarus (Paid)",
    flag: "belarus",
    config_function: "belarus-config",
    sort: 20,
  },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: CORS });
}

function readString(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function readInt(value: unknown, min: number, max: number, fallback: number): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(parsed)));
}

function sanitizePlan(raw: unknown): Plan | null {
  if (!raw || typeof raw !== "object") return null;
  const value = raw as Record<string, unknown>;
  const id = readString(value.id, 32);
  if (!PLAN_IDS.test(id)) return null;

  const days = readInt(value.days, 1, 3650, 0);
  const price = readInt(value.price_rub, 1, 100_000, 0);
  if (days === 0 || price === 0) return null;

  const badge = readString(value.badge, 12);
  const labelRu = readString(value.label_ru, 80) || `${days} дней`;
  const labelEn = readString(value.label_en, 80) || `${days} days`;
  return {
    id,
    days,
    price_rub: price,
    badge: badge || null,
    label_ru: labelRu,
    label_en: labelEn,
  };
}

function sanitizeNode(raw: unknown): PaidNode | null {
  if (!raw || typeof raw !== "object") return null;
  const value = raw as Record<string, unknown>;
  const id = readString(value.id, 48);
  const kind = readString(value.kind, 16);
  const configFunction = readString(value.config_function, 64);
  const flag = readString(value.flag, 32);
  if (!NODE_IDS.test(id)) return null;
  if (!NODE_KINDS.has(kind)) return null;
  if (!FUNCTION_NAMES.test(configFunction)) return null;
  if (!FLAGS.test(flag)) return null;
  if (value.enabled === false) return null;

  return {
    id,
    kind: kind as PaidNode["kind"],
    label_ru: readString(value.label_ru, 80) || id,
    label_en: readString(value.label_en, 80) || id,
    flag,
    config_function: configFunction,
    sort: readInt(value.sort, -100_000, 100_000, 100),
  };
}

function sanitizePlans(payload: unknown): Plan[] {
  if (!Array.isArray(payload)) return DEFAULT_PLANS;
  const seen = new Set<string>();
  const plans: Plan[] = [];
  for (const item of payload) {
    const plan = sanitizePlan(item);
    if (!plan || seen.has(plan.id)) continue;
    seen.add(plan.id);
    plans.push(plan);
  }
  if (plans.length === 0) return DEFAULT_PLANS;
  return plans.sort((a, b) => a.days - b.days || a.price_rub - b.price_rub);
}

function sanitizeNodes(payload: unknown): PaidNode[] {
  if (!Array.isArray(payload)) return DEFAULT_NODES;
  // An empty array is a deliberate "no paid nodes" state: it must remove the
  // buttons, not silently bring France and Belarus back.
  if (payload.length === 0) return [];
  const seen = new Set<string>();
  const nodes: PaidNode[] = [];
  for (const item of payload) {
    const node = sanitizeNode(item);
    if (!node || seen.has(node.id)) continue;
    seen.add(node.id);
    nodes.push(node);
  }
  if (nodes.length === 0) return DEFAULT_NODES;
  return nodes.sort((a, b) => a.sort - b.sort || a.id.localeCompare(b.id));
}

function newestTimestamp(rows: ConfigRow[]): string | null {
  let newest: string | null = null;
  for (const row of rows) {
    if (!row.updated_at) continue;
    if (!newest || row.updated_at > newest) newest = row.updated_at;
  }
  return newest;
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS });
  }
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }
    const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data, error } = await service
      .from("app_config")
      .select("id,payload,updated_at")
      .in("id", ["subscription_plans", "paid_nodes"]);
    if (error) throw new Error(`app_config lookup failed: ${error.message}`);

    const rows = (data ?? []) as ConfigRow[];
    const byId = new Map(rows.map((row) => [row.id, row.payload]));
    const plans = sanitizePlans(byId.get("subscription_plans"));
    const nodes = sanitizeNodes(byId.get("paid_nodes"));
    const minPrice = plans.reduce((min, plan) => Math.min(min, plan.price_rub), plans[0].price_rub);

    return json({
      version: newestTimestamp(rows),
      plans,
      paid_nodes: nodes,
      min_price_rub: minPrice,
    });
  } catch (error) {
    console.error(
      "app-config failed:",
      error instanceof Error ? error.message : error,
    );
    // A config outage must not leave the app without prices or paid nodes.
    return json({
      version: null,
      plans: DEFAULT_PLANS,
      paid_nodes: DEFAULT_NODES,
      min_price_rub: 40,
      fallback: true,
    }, 200);
  }
});
