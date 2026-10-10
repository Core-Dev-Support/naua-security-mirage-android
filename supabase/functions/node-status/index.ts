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

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
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
    detail: row.detail ?? null,
    core: row.core ?? null,
    changed_at: row.changed_at,
    reported_at: row.reported_at,
  };
}

async function writeState(req: Request) {
  if (!NODE_TOKEN) return json({ error: "token not configured" }, 500);
  const provided = req.headers.get("x-node-token") ?? "";
  if (provided.length !== NODE_TOKEN.length || provided !== NODE_TOKEN) {
    return json({ error: "forbidden" }, 403);
  }

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const node = typeof payload.node === "string" && payload.node.trim() ? payload.node.trim() : "france";
  const state = typeof payload.state === "string" && payload.state.trim() ? payload.state.trim() : "unknown";
  const detail = typeof payload.detail === "string" && payload.detail.trim() ? payload.detail.trim() : null;
  const core = typeof payload.core === "string" && payload.core.trim() ? payload.core.trim() : null;
  const changedAt = typeof payload.changed_at === "string" && payload.changed_at
    ? payload.changed_at
    : new Date().toISOString();

  const db = client();
  const { error } = await db.from("node_status").upsert(
    {
      node,
      state,
      detail,
      core,
      changed_at: changedAt,
      reported_at: new Date().toISOString(),
    },
    { onConflict: "node" },
  );
  if (error) return json({ error: error.message }, 500);
  return json({ ok: true, node, state, changed_at: changedAt });
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
    return json(await readState(node));
  } catch (e) {
    return json({ error: String((e as Error).message ?? e) }, 500);
  }
});
