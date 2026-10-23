import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const H1CLOUD_BASE_URL = (Deno.env.get("H1CLOUD_BASE_URL") || "").replace(/\/+$/, "");
const H1CLOUD_USERNAME = Deno.env.get("H1CLOUD_USERNAME") || "";
const H1CLOUD_PASSWORD = Deno.env.get("H1CLOUD_PASSWORD") || "";
const H1CLOUD_API_TOKEN = Deno.env.get("H1CLOUD_API_TOKEN") || "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

const CLIENT_DEVICES = 0;
const CLIENT_TRAFFIC_GB = 0;
const API_TIMEOUT_MS = 20_000;
const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

type H1Client = {
  name: string;
  uuid: string;
  status?: string;
  banned?: boolean;
  expires_at?: number;
  left_days?: number;
  traffic_used_bytes?: number;
};

type H1InboundLink = {
  id?: string;
  tag?: string;
  protocol?: string;
  network?: string;
  security?: string;
  port?: number;
  link?: string;
};

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  belarus_vless_key: string | null;
  belarus_client_uuid: string | null;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

async function panelCall(
  method: string,
  path: string,
  token: string | null,
  body?: unknown,
): Promise<{ status: number; parsed: Record<string, unknown> }> {
  if (!H1CLOUD_BASE_URL) throw new Error("H1Cloud panel address is not configured");
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(`${H1CLOUD_BASE_URL}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(API_TIMEOUT_MS),
  });
  const text = await response.text();
  let parsed: Record<string, unknown> = {};
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`H1Cloud returned a non-JSON reply to ${method} ${path}`);
  }
  return { status: response.status, parsed };
}

async function loginToPanel(): Promise<string> {
  if (!H1CLOUD_USERNAME || !H1CLOUD_PASSWORD) {
    throw new Error("H1Cloud panel credentials are incomplete");
  }
  const { status, parsed } = await panelCall("POST", "/auth/login", null, {
    username: H1CLOUD_USERNAME,
    password: H1CLOUD_PASSWORD,
  });
  const token = typeof parsed.token === "string" ? parsed.token : "";
  if (status !== 200 || !token) {
    throw new Error(`H1Cloud login was rejected with HTTP ${status}`);
  }
  return token;
}

async function resolvePanelToken(): Promise<string> {
  if (H1CLOUD_API_TOKEN) return H1CLOUD_API_TOKEN;
  return await loginToPanel();
}

async function listClients(token: string): Promise<H1Client[]> {
  const { status, parsed } = await panelCall("GET", "/clients", token);
  if (status !== 200 || parsed.ok === false) {
    throw new Error(`H1Cloud client list failed with HTTP ${status}`);
  }
  const clients = parsed.clients;
  return Array.isArray(clients) ? (clients as H1Client[]) : [];
}

async function createPanelClient(
  token: string,
  name: string,
  days: number,
): Promise<{ client: H1Client | null; nameTaken: boolean }> {
  const { status, parsed } = await panelCall("POST", "/create", token, {
    name,
    days,
    devices: CLIENT_DEVICES,
    traffic_gb: CLIENT_TRAFFIC_GB,
    manual: 1,
  });
  if (status === 409) {
    return { client: null, nameTaken: true };
  }
  if (status !== 200 && status !== 201) {
    throw new Error(
      `H1Cloud refused to create a client with HTTP ${status}: ${String(parsed.error ?? "unknown")}`,
    );
  }
  const client = parsed.client;
  return {
    client: client && typeof client === "object" ? (client as H1Client) : null,
    nameTaken: false,
  };
}

function clientNameCandidates(baseName: string): string[] {
  return [baseName, `${baseName}_v2`, `${baseName}_v3`, `${baseName}_v4`, `${baseName}_v5`];
}

async function findOrCreateClient(
  token: string,
  baseName: string,
  days: number,
): Promise<H1Client> {
  const clients = await listClients(token);
  const byName = new Map<string, H1Client>();
  for (const client of clients) {
    if (client.name) byName.set(client.name.toLowerCase(), client);
  }

  for (const candidate of clientNameCandidates(baseName)) {
    const existing = byName.get(candidate.toLowerCase());
    if (existing) return existing;

    const result = await createPanelClient(token, candidate, days);
    if (result.client) return result.client;
    if (!result.nameTaken) {
      throw new Error("The H1Cloud client could not be created or found");
    }
    // 409 name_taken_by_inbound_client: имя занято клиентом, которого нет
    // в /clients (обычно старый inbound-клиент). Пробуем следующий суффикс.
  }

  throw new Error("The H1Cloud client could not be created or found");
}

function pickLink(client: Record<string, unknown>): string {
  const direct = typeof client.link === "string" ? client.link : "";
  if (direct.startsWith("vless://")) return direct;

  const links = client.links;
  if (links && typeof links === "object" && !Array.isArray(links)) {
    for (const value of Object.values(links as Record<string, unknown>)) {
      if (typeof value === "string" && value.startsWith("vless://")) return value;
    }
  }

  const inbounds = client.inbound_links;
  if (Array.isArray(inbounds)) {
    for (const entry of inbounds) {
      const link = (entry as H1InboundLink)?.link;
      if (typeof link === "string" && link.startsWith("vless://")) return link;
    }
  }
  return "";
}

async function findLink(token: string, name: string): Promise<{ link: string; uuid: string } | null> {
  const { status, parsed } = await panelCall("GET", `/info?name=${encodeURIComponent(name)}`, token);
  if (status !== 200 || parsed.ok === false) return null;
  const client = parsed.client;
  if (!client || typeof client !== "object") return null;

  const record = client as Record<string, unknown>;
  if (record.banned === true) throw new Error("The H1Cloud client for this account is banned");
  const statusText = typeof record.status === "string" ? record.status : "";
  if (statusText && statusText !== "active") {
    throw new Error(`The H1Cloud client for this account is ${statusText}`);
  }

  const link = pickLink(record);
  if (!link) return null;

  const uuid = link.replace(/^vless:\/\//, "").split("@")[0].split("?")[0].trim();
  if (!UUID_PATTERN.test(uuid)) {
    throw new Error("The H1Cloud link does not carry a usable client identifier");
  }
  return { link, uuid };
}

function daysUntil(paidUntil: string | null): number {
  if (!paidUntil) return 0;
  const target = new Date(paidUntil).getTime();
  if (!Number.isFinite(target)) return 0;
  const remaining = target - Date.now();
  if (remaining <= 0) return 0;
  return Math.max(1, Math.ceil(remaining / 86_400_000));
}

function fingerprint(text: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, "0");
}

function clientNameFor(email: string): string {
  const lowered = email.trim().toLowerCase();
  const safe = lowered.replace(/[^a-z0-9._-]+/g, "_").slice(0, 46);
  return `mrge_${safe}_${fingerprint(lowered)}`;
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
      .select("user_id,email,is_active,plan,paid_until,belarus_vless_key,belarus_client_uuid")
      .eq("user_id", userId)
      .limit(1);
    if (subError) throw new Error(`Subscription lookup failed: ${subError.message}`);

    const row = (rows && rows[0]) as SubscriptionRow | undefined;
    if (!row || !row.is_active) {
      return json({ error: "No active subscription" }, 403);
    }

    const paidUntil = row.paid_until ? new Date(row.paid_until) : null;
    // Без срока платного периода ключ не выдаём: запись с is_active=true и
    // paid_until=null иначе открывала бы узел бессрочно.
    if (!paidUntil || !Number.isFinite(paidUntil.getTime())) {
      return json({ error: "Subscription has no expiry" }, 403);
    }
    if (paidUntil.getTime() <= Date.now()) {
      return json({ error: "Subscription expired" }, 403);
    }

    const days = daysUntil(row.paid_until);
    if (days <= 0) return json({ error: "Subscription expired" }, 403);

    const panelToken = await resolvePanelToken();
    const wantedName = clientNameFor(email);

    const client = await findOrCreateClient(panelToken, wantedName, days);
    const clientName = client.name || wantedName;

    const resolved = await findLink(panelToken, clientName);
    if (!resolved) {
      throw new Error("The H1Cloud client carries no VLESS link yet");
    }

    // Своё название узла: H1Cloud отдаёт служебное, вида
    // «mrge_<почта>_<хэш> · custom-vless-…», и его видно в клиенте.
    const namedLink = `${resolved.link.split("#")[0]}#${
      encodeURIComponent("NAUA Security Mirage Беларусь (Платный)")
    }`;

    if (row.belarus_client_uuid !== resolved.uuid || row.belarus_vless_key !== namedLink) {
      await service
        .from("subscriptions")
        .update({
          belarus_client_uuid: resolved.uuid,
          belarus_vless_key: namedLink,
          email,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", userId);
    }

    return json({
      vlessKey: namedLink,
      clientUuid: resolved.uuid,
      clientName,
      paidUntil: row.paid_until,
      plan: row.plan,
    });
  } catch (error) {
    console.error(
      "belarus-config failed:",
      error instanceof Error ? error.message : error,
    );
    return json({ error: "Unable to resolve the Belarus node right now" }, 502);
  }
});