import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const H1CLOUD_BASE_URL = (Deno.env.get("H1CLOUD_BASE_URL") || "http://by2.h1cloud.net:25009")
  .replace(/\/+$/, "");
const H1CLOUD_USERNAME = Deno.env.get("H1CLOUD_USERNAME") || "";
const H1CLOUD_PASSWORD = Deno.env.get("H1CLOUD_PASSWORD") || "";

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

async function listClients(token: string): Promise<H1Client[]> {
  const { status, parsed } = await panelCall("GET", "/clients", token);
  if (status !== 200 || parsed.ok === false) {
    throw new Error(`H1Cloud client list failed with HTTP ${status}`);
  }
  const clients = parsed.clients;
  return Array.isArray(clients) ? (clients as H1Client[]) : [];
}

async function createPanelClient(token: string, name: string, days: number): Promise<H1Client | null> {
  const { status, parsed } = await panelCall("POST", "/create", token, {
    name,
    days,
    devices: CLIENT_DEVICES,
    traffic_gb: CLIENT_TRAFFIC_GB,
    manual: 1,
  });
  if (status === 409) return null;
  if (status !== 200 && status !== 201) {
    throw new Error(
      `H1Cloud refused to create a client with HTTP ${status}: ${String(parsed.error ?? "unknown")}`,
    );
  }
  const client = parsed.client;
  return client && typeof client === "object" ? (client as H1Client) : null;
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
  if (!paidUntil) return 30;
  const target = new Date(paidUntil).getTime();
  if (!Number.isFinite(target)) return 30;
  const remaining = target - Date.now();
  if (remaining <= 0) throw new Error("Subscription expired");
  return Math.max(1, Math.ceil(remaining / 86_400_000));
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

    const days = daysUntil(row.paid_until);
    const panelToken = await loginToPanel();

    const existing = (await listClients(panelToken))
      .find((client) => (client.name || "").toLowerCase() === email.toLowerCase());
    let clientName = existing?.name || email;

    if (!existing) {
      const created = await createPanelClient(panelToken, email, days);
      if (!created) {
        const raced = (await listClients(panelToken))
          .find((client) => (client.name || "").toLowerCase() === email.toLowerCase());
        if (!raced) throw new Error("The H1Cloud client could not be created or found");
        clientName = raced.name;
      } else {
        clientName = created.name;
      }
    }

    const resolved = await findLink(panelToken, clientName);
    if (!resolved) {
      throw new Error("The H1Cloud client carries no VLESS link yet");
    }

    if (row.belarus_client_uuid !== resolved.uuid || row.belarus_vless_key !== resolved.link) {
      await service
        .from("subscriptions")
        .update({
          belarus_client_uuid: resolved.uuid,
          belarus_vless_key: resolved.link,
          email,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", userId);
    }

    return json({
      vlessKey: resolved.link,
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