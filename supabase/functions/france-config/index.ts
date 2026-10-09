import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const THREE_X_UI_BASE_URL = Deno.env.get("THREE_X_UI_BASE_URL") || "";
const THREE_X_UI_USER = Deno.env.get("THREE_X_UI_USERNAME") || Deno.env.get("THREE_X_UI_USER") || "";
const THREE_X_UI_PASS = Deno.env.get("THREE_X_UI_PASSWORD") || Deno.env.get("THREE_X_UI_PASS") || "";
const THREE_X_UI_INBOUND_ID = Number(Deno.env.get("THREE_X_UI_INBOUND_ID") || "2");
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const FRANCE_HOST = Deno.env.get("FRANCE_HOST") || "";
const FRANCE_PORT = Deno.env.get("FRANCE_PORT") || "443";
const FRANCE_PBK = Deno.env.get("FRANCE_PBK") || "";
const FRANCE_SNI = Deno.env.get("FRANCE_SNI") || "";
const FRANCE_SID = Deno.env.get("FRANCE_SID") || "";
const FRANCE_SPX = Deno.env.get("FRANCE_SPX") || "/";
const FRANCE_FINGERPRINT = Deno.env.get("FRANCE_FINGERPRINT") || "chrome";

const XHTTP_MODE = "packet-up";
const XHTTP_PATH = "/api/v1/collect";
const XHTTP_FINGERPRINT = "edge";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

type ThreeXClient = {
  id: string;
  email?: string;
  flow?: string;
  enable?: boolean;
  expiryTime?: number;
};

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  vless_key: string | null;
  client_uuid: string | null;
  flow: string | null;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function assert3xSuccess(response: Response, body: string, operation: string, allowEmpty = false): void {
  if (!response.ok) throw new Error(`${operation} failed with HTTP ${response.status}`);
  if (!body) {
    if (allowEmpty) return;
    throw new Error(`${operation} returned an empty response`);
  }
  try {
    const parsed = JSON.parse(body);
    if (!parsed || parsed.success !== true) throw new Error(`${operation} was rejected by 3X-UI`);
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error(`${operation} returned invalid JSON`);
    throw error;
  }
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

async function getInboundClient(
  cookie: string,
  userId: string,
  email: string,
  clientUuid: string | null,
): Promise<ThreeXClient | null> {
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/get/${THREE_X_UI_INBOUND_ID}`, {
    headers: { Cookie: cookie },
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI inbound lookup");
  const root = JSON.parse(body);
  const rawSettings = root.obj?.settings;
  const settings = typeof rawSettings === "string" ? JSON.parse(rawSettings) : (rawSettings || {});
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

function vlessUrl(uuid: string): string {
  const query = new URLSearchParams({
    type: "xhttp",
    security: "reality",
    pbk: FRANCE_PBK,
    fp: XHTTP_FINGERPRINT,
    sni: FRANCE_SNI,
    host: FRANCE_SNI,
    mode: XHTTP_MODE,
    path: XHTTP_PATH,
  });
  return `vless://${uuid}@${FRANCE_HOST}:${FRANCE_PORT}?${query.toString()}#NAUA%20Mirage%20France%20(Premium)`;
}

async function ensureClient(
  cookie: string,
  userId: string,
  email: string,
  existing: SubscriptionRow | null,
  expiryMs: number,
): Promise<{ uuid: string; flow: string }> {
  const found = await getInboundClient(cookie, userId, email, existing?.client_uuid || null);
  if (found) {
    return { uuid: found.id, flow: found.flow ?? "" };
  }

  const uuid = crypto.randomUUID();
  const client = {
    id: uuid,
    email,
    flow: "",
    limitIp: 0,
    totalGB: 0,
    expiryTime: expiryMs,
    enable: true,
    tgId: "",
    subId: uuid.replace(/-/g, "").slice(0, 16),
  };
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/addClient`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Cookie: cookie },
    body: JSON.stringify({
      id: THREE_X_UI_INBOUND_ID,
      settings: JSON.stringify({ clients: [client] }),
    }),
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI addClient", true);
  const verified = await getInboundClient(cookie, userId, email, uuid);
  if (!verified || verified.id.toLowerCase() !== uuid.toLowerCase()) {
    throw new Error("3X-UI addClient was not verified in the inbound");
  }
  return { uuid: verified.id, flow: verified.flow ?? "" };
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }

    const authHeader = req.headers.get("Authorization") || "";
    const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
    if (!token) return json({ error: "Missing bearer token" }, 401);

    const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: userData, error: userError } = await userClient.auth.getUser(token);
    if (userError || !userData?.user?.email) {
      return json({ error: "Invalid session" }, 401);
    }
    const userId = userData.user.id;
    const email = userData.user.email as string;

    const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: rows, error: subError } = await service
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow")
      .eq("user_id", userId)
      .limit(1);
    if (subError) throw new Error(`Subscription lookup failed: ${subError.message}`);

    const row = (rows && rows[0]) as SubscriptionRow | undefined;
    if (!row || !row.is_active) {
      return json({ error: "No active subscription" }, 403);
    }

    const paidUntil = row.paid_until ? new Date(row.paid_until) : null;
    if (paidUntil && paidUntil.getTime() <= Date.now()) {
      return json({ error: "Subscription expired" }, 403);
    }

    const cookie = await loginTo3xUi();
    const expiryMs = paidUntil ? paidUntil.getTime() : Date.now() + 30 * 24 * 60 * 60 * 1000;
    const { uuid, flow } = await ensureClient(cookie, userId, email, row, expiryMs);
    const key = vlessUrl(uuid);

    if (row.client_uuid !== uuid || row.vless_key !== key || row.flow !== flow) {
      await service
        .from("subscriptions")
        .update({ client_uuid: uuid, vless_key: key, flow, email, updated_at: new Date().toISOString() })
        .eq("user_id", userId);
    }

    return json({
      vlessKey: key,
      clientUuid: uuid,
      flow,
      paidUntil: row.paid_until,
      plan: row.plan,
    });
  } catch (error) {

    console.error("france-config failed:", error instanceof Error ? error.message : error);
    return json({ error: "Unable to resolve the paid node right now" }, 502);
  }
});
