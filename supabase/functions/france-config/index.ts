import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { h1EnsureNode } from "../_shared/h1panel.ts";

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

function detectProtocolTag(link: string): string {
  if (link.startsWith("hysteria2://") || link.startsWith("hy2://")) {
    return "Hysteria 2";
  }
  if (link.startsWith("vless://")) {
    const lower = link.toLowerCase();
    if (lower.includes("type=xhttp") || lower.includes("network=xhttp") || lower.includes("xhttp")) {
      return "XHTTP";
    }
    return "VLESS";
  }
  return "";
}

function nameNlLink(rawLink: string): string {
  const proto = detectProtocolTag(rawLink);
  const label = proto ? `🇳🇱 Нидерланды (${proto})` : "🇳🇱 Нидерланды (XHTTP)";
  return `${rawLink.split("#")[0]}#${encodeURIComponent(label)}`;
}

async function verifyTelegramToken(token: string, secret: string): Promise<{ tgId: number } | null> {
  if (!token || !token.startsWith("tgs_") || !secret) return null;
  const parts = token.slice(4).split("_");
  if (parts.length !== 3) return null;
  const [tgIdStr, tsStr, sig] = parts;
  const tgId = Number(tgIdStr);
  const ts = Number(tsStr);
  if (!Number.isFinite(tgId) || tgId <= 0 || !Number.isFinite(ts)) return null;
  if (Date.now() - ts > 90 * 24 * 60 * 60 * 1000) return null;

  try {
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );
    const data = new TextEncoder().encode(`${tgId}:${ts}`);
    const sigBytes = await crypto.subtle.sign("HMAC", key, data);
    const expectedSig = Array.from(new Uint8Array(sigBytes))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    if (expectedSig !== sig) return null;
    return { tgId };
  } catch {
    return null;
  }
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }

    const url = new URL(req.url);
    const requestedProto = (url.searchParams.get("proto") || "").toLowerCase();

    const authHeader = req.headers.get("Authorization") || "";
    const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
    if (!token) return json({ error: "Missing bearer token" }, 401);

    let userId: string;
    let email: string;

    const tgSession = await verifyTelegramToken(token, SUPABASE_SERVICE_ROLE_KEY);
    if (tgSession) {
      userId = `tg_${tgSession.tgId}`;
      email = `Telegram: ${tgSession.tgId}`;
    } else if (token === SUPABASE_SERVICE_ROLE_KEY && (url.searchParams.get("user_id") || url.searchParams.get("email"))) {
      userId = url.searchParams.get("user_id") || "";
      email = url.searchParams.get("email") || "";
    } else {
      const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
        auth: { persistSession: false, autoRefreshToken: false },
      });
      const { data: userData, error: userError } = await userClient.auth.getUser(token);
      if (userError || !userData?.user?.email) {
        return json({ error: "Invalid session" }, 401);
      }
      userId = userData.user.id;
      email = userData.user.email as string;
    }

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

    const node = await h1EnsureNode(email, row.paid_until);
    const trafficSeen = node.trafficSeen;

    const namedLinks = (node.links && node.links.length > 0 ? node.links : [node.link])
      .map(nameNlLink);

    let chosenKey = namedLinks[0] || nameNlLink(node.link);
    if (requestedProto === "hysteria2" || requestedProto === "hy2") {
      const hy = namedLinks.find((l) => l.startsWith("hysteria2://") || l.startsWith("hy2://"));
      if (hy) chosenKey = hy;
    } else if (requestedProto === "xhttp") {
      const xh = namedLinks.find((l) => l.toLowerCase().includes("type=xhttp"));
      if (xh) chosenKey = xh;
    }

    const key = chosenKey;

    if (row.client_uuid !== node.uuid || row.vless_key !== namedLinks[0] || row.flow !== "") {
      await service
        .from("subscriptions")
        .update({
          client_uuid: node.uuid,
          vless_key: namedLinks[0],
          flow: "",
          email,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", userId);
    }

    return json({
      vlessKey: key,
      links: namedLinks,
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
