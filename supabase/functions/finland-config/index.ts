import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

// Finland is a generic VLESS node from `app_config.paid_nodes`.
//
// The app never names France or Finland in code for this path: it calls the
// `config_function` written in the table and parses the returned `vlessKey`.
// That is why adding this node needs no APK update. This function is the
// server-side half of the node.
//
// A real Finnish panel is not connected yet. Until FINLAND_* secrets exist,
// the function uses the France panel, so the test button really connects
// (through France) and exercises the whole generic path: separate function,
// separate key cache and its own log entries. When the Finnish panel appears,
// set FINLAND_3X_UI_* and FINLAND_* secrets; no app rebuild is needed.
const FINLAND_PANEL_URL = (Deno.env.get("FINLAND_3X_UI_BASE_URL") || "").replace(/\/+$/, "");
const FINLAND_HOST_RAW = Deno.env.get("FINLAND_HOST") || "";
const USE_FINLAND = Boolean(FINLAND_PANEL_URL || FINLAND_HOST_RAW);
const SOURCE = USE_FINLAND ? "finland" : "france-fallback";

const THREE_X_UI_BASE_URL = (USE_FINLAND
  ? FINLAND_PANEL_URL
  : (Deno.env.get("THREE_X_UI_BASE_URL") || "")).replace(/\/+$/, "");
const THREE_X_UI_USER = USE_FINLAND
  ? (Deno.env.get("FINLAND_3X_UI_USERNAME") || "")
  : (Deno.env.get("THREE_X_UI_USERNAME") || Deno.env.get("THREE_X_UI_USER") || "");
const THREE_X_UI_PASS = USE_FINLAND
  ? (Deno.env.get("FINLAND_3X_UI_PASSWORD") || "")
  : (Deno.env.get("THREE_X_UI_PASSWORD") || Deno.env.get("THREE_X_UI_PASS") || "");
const THREE_X_UI_INBOUND_ID = Number(
  (USE_FINLAND
    ? Deno.env.get("FINLAND_3X_UI_INBOUND_ID")
    : Deno.env.get("THREE_X_UI_INBOUND_ID")) || "2",
);
const NODE_HOST = USE_FINLAND ? FINLAND_HOST_RAW : (Deno.env.get("FRANCE_HOST") || "");
const NODE_PORT = (USE_FINLAND
  ? Deno.env.get("FINLAND_PORT")
  : Deno.env.get("FRANCE_PORT")) || "443";
const NODE_PBK = (USE_FINLAND
  ? Deno.env.get("FINLAND_PBK")
  : Deno.env.get("FRANCE_PBK")) || "";
const NODE_SNI = (USE_FINLAND
  ? Deno.env.get("FINLAND_SNI")
  : Deno.env.get("FRANCE_SNI")) || "";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

const NODE_ID = "premium_finland_test";
const NODE_LABEL = "Финляндия (Тест)";

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
  up?: number;
  down?: number;
};

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  client_uuid: string | null;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

/**
 * One structured line per event. It intentionally contains no VLESS key,
 * panel URL or full UUID: logs must identify the node without leaking it.
 */
function logEvent(event: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({
    fn: "finland-config",
    node: NODE_ID,
    label: NODE_LABEL,
    source: SOURCE,
    event,
    ...fields,
  }));
}

// A cold start leaves a trace even before the first request, so the node is
// visible in the function log while the test button is being checked.
logEvent("ready");

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
    throw new Error(USE_FINLAND
      ? "Finland 3X-UI configuration is incomplete"
      : "3X-UI configuration is incomplete");
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

async function normalizeClientFlow(
  cookie: string,
  found: ThreeXClient,
): Promise<void> {
  const raw = found as unknown as Record<string, unknown>;
  const updated = { ...raw, flow: "" };
  const response = await fetch(
    `${THREE_X_UI_BASE_URL}/panel/api/inbounds/updateClient/${found.id}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", Cookie: cookie },
      body: JSON.stringify({
        id: THREE_X_UI_INBOUND_ID,
        settings: JSON.stringify({ clients: [updated] }),
      }),
    },
  );
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI updateClient", true);
}

function vlessUrl(uuid: string): string {
  const query = new URLSearchParams({
    type: "xhttp",
    security: "reality",
    pbk: NODE_PBK,
    fp: XHTTP_FINGERPRINT,
    sni: NODE_SNI,
    host: NODE_SNI,
    mode: XHTTP_MODE,
    path: XHTTP_PATH,
  });
  return `vless://${uuid}@${NODE_HOST}:${NODE_PORT}?${query.toString()}#${
    encodeURIComponent(`NAUA Security Mirage ${NODE_LABEL}`)
  }`;
}

async function ensureClient(
  cookie: string,
  userId: string,
  email: string,
  existing: SubscriptionRow | null,
  expiryMs: number,
): Promise<{ uuid: string; flow: string; trafficSeen: boolean }> {
  const found = await getInboundClient(cookie, userId, email, existing?.client_uuid || null);
  if (found) {
    const trafficSeen = Number(found.up || 0) > 0 || Number(found.down || 0) > 0;
    if ((found.flow ?? "") !== "") {
      await normalizeClientFlow(cookie, found);
      const verified = await getInboundClient(cookie, userId, email, found.id);
      if (!verified || (verified.flow ?? "") !== "") {
        throw new Error("3X-UI flow normalization was not applied");
      }
      return {
        uuid: verified.id,
        flow: "",
        trafficSeen: Number(verified.up || 0) > 0 || Number(verified.down || 0) > 0,
      };
    }
    return { uuid: found.id, flow: found.flow ?? "", trafficSeen };
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
  return { uuid: verified.id, flow: verified.flow ?? "", trafficSeen: false };
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
    logEvent("authorized", { user: userId.slice(0, 8) });

    const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: rows, error: subError } = await service
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,client_uuid")
      .eq("user_id", userId)
      .limit(1);
    if (subError) throw new Error(`Subscription lookup failed: ${subError.message}`);

    const row = (rows && rows[0]) as SubscriptionRow | undefined;
    if (!row || !row.is_active) {
      logEvent("denied", { user: userId.slice(0, 8), reason: "no-active-subscription" });
      return json({ error: "No active subscription" }, 403);
    }

    const paidUntil = row.paid_until ? new Date(row.paid_until) : null;
    // A row without an expiry must not open a paid node.
    if (!paidUntil || !Number.isFinite(paidUntil.getTime())) {
      logEvent("denied", { user: userId.slice(0, 8), reason: "no-expiry" });
      return json({ error: "Subscription has no expiry" }, 403);
    }
    if (paidUntil.getTime() <= Date.now()) {
      logEvent("denied", { user: userId.slice(0, 8), reason: "expired" });
      return json({ error: "Subscription expired" }, 403);
    }

    const cookie = await loginTo3xUi();
    const expiryMs = paidUntil.getTime();
    const { uuid, flow, trafficSeen } = await ensureClient(cookie, userId, email, row, expiryMs);
    const key = vlessUrl(uuid);

    // France keeps its key in subscriptions.vless_key. A generic node must not
    // overwrite that column: it has its own cache keyed by node id in the app.
    logEvent("key-issued", {
      user: userId.slice(0, 8),
      uuid: uuid.slice(0, 8),
      trafficSeen,
    });

    return json({
      vlessKey: key,
      clientUuid: uuid,
      flow,
      trafficSeen,
      paidUntil: row.paid_until,
      plan: row.plan,
      node: NODE_ID,
      source: SOURCE,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    logEvent("failed", { error: message });
    return json({ error: "Unable to resolve the paid node right now" }, 502);
  }
});
