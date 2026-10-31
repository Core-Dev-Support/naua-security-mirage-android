import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import {
  h1EnsureNode,
  h1PanelConfigured,
} from "../_shared/h1panel.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
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
    // Строка без срока (например, старый free с is_active=true) не должна
    // открывать платный узел: ключ выдаётся только на оплаченный период.
    if (!paidUntil || !Number.isFinite(paidUntil.getTime())) {
      return json({ error: "Subscription has no expiry" }, 403);
    }
    if (paidUntil.getTime() <= Date.now()) {
      return json({ error: "Subscription expired" }, 403);
    }
    if (!h1PanelConfigured()) {
      throw new Error("H1 panel configuration is incomplete");
    }

    const node = await h1EnsureNode(email, row.paid_until);
    const key = node.link;
    const trafficSeen = node.trafficSeen;

    if (row.client_uuid !== node.uuid || row.vless_key !== key || row.flow !== "") {
      await service
        .from("subscriptions")
        .update({
          client_uuid: node.uuid,
          vless_key: key,
          flow: "",
          email,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", userId);
    }

    return json({
      vlessKey: key,
      clientUuid: node.uuid,
      flow: "",
      trafficSeen,
      paidUntil: row.paid_until,
      plan: row.plan,
    });
  } catch (error) {
    console.error("france-config failed:", error instanceof Error ? error.message : error);
    return json({ error: "Unable to resolve the paid node right now" }, 502);
  }
});
