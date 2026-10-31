// H1 panel client for the paid Switzerland node.
//
// The old France provider used the 3X-UI session API. The H1 panel speaks a
// token API instead; this module mirrors the proven belarus-config flows:
// list clients, create on 409 with name suffixes, extend via /api/edit and
// take the ready VLESS link from the panel.
//
// Supabase runs outside Russia, so H1_PANEL_URL must point at the direct-*
// panel host.

const PANEL_URL = (Deno.env.get("H1_PANEL_URL") || "").replace(/\/+$/, "");
const PANEL_TOKEN = Deno.env.get("H1_PANEL_TOKEN") || "";
const INBOUND_ID = Deno.env.get("H1_INBOUND_ID") || "";

const CLIENT_DEVICES = 0;
const CLIENT_TRAFFIC_GB = 0;
const API_TIMEOUT_MS = 8_000;
const TOTAL_PANEL_BUDGET_MS = 14_000;
const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export type H1Client = {
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
  link?: string;
};

/**
 * Keeps the whole panel interaction inside the app's HTTP timeout. A slow
 * panel must end with a clear 502 instead of a client-side SocketTimeout.
 */
class PanelBudget {
  private readonly deadline: number;

  constructor(totalMs: number) {
    this.deadline = Date.now() + totalMs;
  }

  remaining(): number {
    return this.deadline - Date.now();
  }

  expired(): boolean {
    return this.remaining() <= 300;
  }
}

async function panelCall(
  method: string,
  path: string,
  body: unknown,
  budget: PanelBudget,
): Promise<{ status: number; parsed: Record<string, unknown> }> {
  if (!PANEL_URL) throw new Error("H1 panel address is not configured");
  if (!PANEL_TOKEN) throw new Error("H1 panel token is not configured");
  if (budget.expired()) throw new Error("H1 panel did not respond in time");

  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${PANEL_TOKEN}`,
  };
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const response = await fetch(`${PANEL_URL}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(Math.min(API_TIMEOUT_MS, budget.remaining())),
  });
  const text = await response.text();
  let parsed: Record<string, unknown> = {};
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`H1 panel returned a non-JSON reply to ${method} ${path}`);
  }
  return { status: response.status, parsed };
}

function asClient(value: unknown): H1Client | null {
  return value && typeof value === "object" ? (value as H1Client) : null;
}

async function listClients(budget: PanelBudget): Promise<H1Client[]> {
  const { status, parsed } = await panelCall("GET", "/clients", undefined, budget);
  if (status !== 200 || parsed.ok === false) {
    throw new Error(`H1 panel client list failed with HTTP ${status}`);
  }
  const clients = parsed.clients;
  return Array.isArray(clients) ? (clients as H1Client[]) : [];
}

async function fetchClient(
  name: string,
  budget: PanelBudget,
): Promise<H1Client | null> {
  const { status, parsed } = await panelCall(
    "GET",
    `/info?name=${encodeURIComponent(name)}`,
    undefined,
    budget,
  );
  if (status !== 200 || parsed.ok === false) return null;
  return asClient(parsed.client);
}

async function createPanelClient(
  name: string,
  days: number,
  budget: PanelBudget,
): Promise<{ client: H1Client | null; nameTaken: boolean }> {
  const body: Record<string, unknown> = {
    name,
    days,
    devices: CLIENT_DEVICES,
    traffic_gb: CLIENT_TRAFFIC_GB,
    manual: 1,
  };
  if (INBOUND_ID) body.inbound_ids = [INBOUND_ID];
  const { status, parsed } = await panelCall("POST", "/create", body, budget);
  if (status === 409) {
    return { client: null, nameTaken: true };
  }
  if (status !== 200 && status !== 201) {
    throw new Error(
      `H1 panel refused to create a client with HTTP ${status}: ${String(parsed.error ?? "unknown")}`,
    );
  }
  return { client: asClient(parsed.client), nameTaken: false };
}

async function extendPanelClient(
  name: string,
  days: number,
  budget: PanelBudget,
): Promise<H1Client> {
  const { status, parsed } = await panelCall(
    "PATCH",
    "/edit",
    { name, days },
    budget,
  );
  if (status !== 200) {
    throw new Error(
      `H1 panel refused to extend a client with HTTP ${status}: ${String(parsed.error ?? "unknown")}`,
    );
  }
  const client = asClient(parsed.client);
  if (!client) throw new Error("H1 panel did not return the extended client");
  return client;
}

/**
 * `left_days` is rounded differently by different panel endpoints. Comparing
 * `expires_at` with the subscription end avoids an `/edit` on every request
 * while still keeping the client valid for the whole paid period.
 */
function needsExtension(client: H1Client, days: number, paidUntilMs: number): boolean {
  if (client.banned === true) return false;
  const status = (client.status || "").toLowerCase();
  if (status !== "active") return true;

  const expiresAt = typeof client.expires_at === "number" ? client.expires_at * 1000 : 0;
  if (expiresAt > 0) {
    return expiresAt < paidUntilMs - 6 * 60 * 60 * 1000;
  }

  const left = typeof client.left_days === "number" ? client.left_days : Number(client.left_days);
  return !Number.isFinite(left) || left < days;
}

async function ensureClientCoversSubscription(
  client: H1Client,
  days: number,
  paidUntilMs: number,
  budget: PanelBudget,
): Promise<H1Client> {
  if (!client.name) throw new Error("H1 panel returned a client without a name");
  if (!needsExtension(client, days, paidUntilMs)) return client;
  try {
    return await extendPanelClient(client.name, days, budget);
  } catch (error) {
    // A still-active client is better than a 502; an expired one will be
    // rejected by the link check below.
    console.warn(
      "h1panel: could not extend the client:",
      error instanceof Error ? error.message : error,
    );
    return client;
  }
}

function clientNameCandidates(baseName: string): string[] {
  return [
    baseName,
    `${baseName}_v2`,
    `${baseName}_v3`,
    `${baseName}_v4`,
    `${baseName}_v5`,
    `${baseName}_v6`,
  ];
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

function daysUntil(paidUntil: string | null): number {
  if (!paidUntil) return 0;
  const target = new Date(paidUntil).getTime();
  if (!Number.isFinite(target)) return 0;
  const remaining = target - Date.now();
  if (remaining <= 0) return 0;
  return Math.max(1, Math.ceil(remaining / 86_400_000));
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

export type H1Node = {
  link: string;
  uuid: string;
  trafficSeen: boolean;
  paidUntil: string | null;
};

// h1EnsureNode — выдаёт ссылку узла для пользователя и продлевает клиента
// до конца оплаченного периода. Вызывают france-config и yoomoney-webhook.
export async function h1EnsureNode(
  email: string,
  paidUntil: string | null,
): Promise<H1Node> {
  const days = daysUntil(paidUntil);
  if (days <= 0) throw new Error("Subscription has no remaining paid period");
  const parsedPaidUntil = paidUntil ? new Date(paidUntil).getTime() : NaN;
  const paidUntilMs = Number.isFinite(parsedPaidUntil)
    ? parsedPaidUntil
    : Date.now() + days * 86_400_000;
  const budget = new PanelBudget(TOTAL_PANEL_BUDGET_MS);

  const clients = await listClients(budget);
  const byName = new Map<string, H1Client>();
  for (const client of clients) {
    if (client.name) byName.set(client.name.toLowerCase(), client);
  }

  const baseName = clientNameFor(email);
  for (const candidate of clientNameCandidates(baseName)) {
    const existing = byName.get(candidate.toLowerCase());
    let client = existing ?? null;

    if (!client) {
      const result = await createPanelClient(candidate, days, budget);
      if (result.client) {
        client = result.client;
      } else if (result.nameTaken) {
        continue;
      } else {
        throw new Error("The H1 panel client could not be created or found");
      }
    }

    const ensured = await ensureClientCoversSubscription(client, days, paidUntilMs, budget);
    const fresh = await fetchClient(ensured.name || candidate, budget) ??
      await fetchClient(candidate, budget);
    if (!fresh) continue;

    if (fresh.banned === true) {
      throw new Error("The H1 panel client for this account is banned");
    }
    const statusText = typeof fresh.status === "string" ? fresh.status : "";
    if (statusText && statusText !== "active") {
      throw new Error(`The H1 panel client for this account is ${statusText}`);
    }

    const link = pickLink(fresh as unknown as Record<string, unknown>);
    if (!link) continue;
    const uuid = link.replace(/^vless:\/\//, "").split("@")[0].split("?")[0].trim();
    if (!UUID_PATTERN.test(uuid)) {
      throw new Error("The H1 panel link does not carry a usable client identifier");
    }
    return {
      link,
      uuid,
      trafficSeen: Number(fresh.traffic_used_bytes || 0) > 0,
      paidUntil,
    };
  }

  throw new Error("The H1 panel client could not be created or found");
}
