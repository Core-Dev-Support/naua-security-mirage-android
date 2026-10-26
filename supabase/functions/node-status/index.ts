import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

// Reports whether the France node is serving. The watchdog on the node pushes its state
// here whenever it changes, so the row carries both the current state and since when it
// started. The app reads it to show the node status without opening a tunnel.
//
// The table has RLS on with no policy, so the anon key that ships in the APK cannot read it.
// Reads go through this function; writes require the shared token the watchdog sends.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const NODE_TOKEN = Deno.env.get("NODE_STATUS_TOKEN") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-node-token",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Cache-Control": "no-store",
};

const MAX_EVENTS = 200;

// The watchdog sends raw Xray logs in `detail`. They can contain a client UUID,
// an e-mail, a VLESS link or an address, and this endpoint is readable with the
// public anon key that ships inside the APK. Never persist or return those raw:
// a leaked UUID plus the public Reality key is a working paid-node credential.
const UUID_RE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const VLESS_RE = /vless:\/\/[^\s"'<>]+/gi;
const JWT_RE = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g;
const IPV4_RE = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;

function sanitizeDetail(value: string | null): string | null {
  if (!value) return null;
  const redacted = value
    .slice(0, 512)
    .replace(VLESS_RE, "<redacted-link>")
    .replace(UUID_RE, "<redacted-id>")
    .replace(EMAIL_RE, "<redacted-email>")
    .replace(JWT_RE, "<redacted-token>")
    .replace(IPV4_RE, "<redacted-ip>");
  return redacted.slice(0, 400);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function client() {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });
}

async function readState(node: string) {
  const db = client();
  const { data, error } = await db
    .from("node_status")
    .select("node,state,detail,core,changed_at,reported_at")
    .eq("node", node)
    .limit(1);
  if (error) throw new Error(error.message);
  const row = Array.isArray(data) && data.length > 0 ? data[0] : null;
  if (!row) {
    return {
      node,
      state: "unknown",
      detail: "no report yet",
      core: null,
      changed_at: null,
      reported_at: null,
    };
  }
  return {
    node: row.node,
    state: row.state,
    detail: sanitizeDetail(row.detail ?? null),
    core: row.core ?? null,
    changed_at: row.changed_at,
    reported_at: row.reported_at,
  };
}

async function writeState(req: Request) {
  if (!NODE_TOKEN) return json({ error: "token not configured" }, 500);
  const provided = req.headers.get("x-node-token") ?? "";
  if (!timingSafeEqual(provided, NODE_TOKEN)) {
    return json({ error: "forbidden" }, 403);
  }

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const node = typeof payload.node === "string" && payload.node.trim()
    ? payload.node.trim().slice(0, 64)
    : "france";
  const state = typeof payload.state === "string" && payload.state.trim()
    ? payload.state.trim().slice(0, 32)
    : "unknown";
  const detail = typeof payload.detail === "string" && payload.detail.trim()
    ? sanitizeDetail(payload.detail.trim())
    : null;
  const core = typeof payload.core === "string" && payload.core.trim()
    ? payload.core.trim().slice(0, 512)
    : null;
  const rawChangedAt = typeof payload.changed_at === "string" ? payload.changed_at : "";
  const parsedChangedAt = Date.parse(rawChangedAt);
  const changedAt = Number.isFinite(parsedChangedAt)
    ? new Date(parsedChangedAt).toISOString()
    : new Date().toISOString();

  const db = client();
  const record = {
    node,
    state,
    detail,
    core,
    changed_at: changedAt,
    reported_at: new Date().toISOString(),
  };
  const { error } = await db.from("node_status").upsert(record, { onConflict: "node" });
  if (error) return json({ error: error.message }, 500);

  // An event is only recorded when the state actually moved, so the history stays a list of
  // incidents rather than a duplicate of the current row every minute.
  let eventSaved = false;
  if (payload.record_event === true) {
    const { error: evErr } = await db.from("node_events").insert({
      node,
      state,
      detail,
      core,
      changed_at: changedAt,
    });
    if (evErr) return json({ error: evErr.message }, 500);
    eventSaved = true;
    await trimEvents(db, node);
  }

  return json({ ok: true, node, state, changed_at: changedAt, event: eventSaved });
}

async function trimEvents(db: ReturnType<typeof client>, node: string) {
  const { data } = await db
    .from("node_events")
    .select("id")
    .eq("node", node)
    .order("created_at", { ascending: false })
    .range(MAX_EVENTS, MAX_EVENTS + 200);
  const ids = Array.isArray(data) ? data.map((r) => r.id as number) : [];
  if (ids.length > 0) {
    await db.from("node_events").delete().in("id", ids);
  }
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method === "POST") {
    try {
      return await writeState(req);
    } catch (e) {
      return json({ error: String((e as Error).message ?? e) }, 500);
    }
  }
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

  try {
    const url = new URL(req.url);
    const node = url.searchParams.get("node")?.trim() || "france";
    const db = client();

    if (url.searchParams.get("history") === "1") {
      const limit = Math.min(Number(url.searchParams.get("limit") || "10") || 10, 50);
      // History only needs the state and timestamps for the in-app incident
      // list. `detail` is deliberately not selected: older rows still contain
      // raw Xray logs with client identifiers, and the app never displays it.
      const { data, error } = await db
        .from("node_events")
        .select("node,state,core,changed_at,created_at")
        .eq("node", node)
        .order("created_at", { ascending: false })
        .limit(limit);
      if (error) throw new Error(error.message);
      return json({ node, events: Array.isArray(data) ? data : [] });
    }

    return json(await readState(node));
  } catch (e) {
    return json({ error: String((e as Error).message ?? e) }, 500);
  }
});
