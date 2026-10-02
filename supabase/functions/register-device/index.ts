import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const token = typeof body.token === "string" ? body.token.trim() : "";
  const appVersion = typeof body.app_version === "string" ? body.app_version : null;
  if (!token) return json({ error: "token is required" }, 400);
  if (token.length > 512) return json({ error: "token is too long" }, 400);

  const auth = req.headers.get("Authorization") ?? "";
  let userId: string | null = null;
  if (auth.startsWith("Bearer ")) {
    const jwt = auth.slice(7).trim();
    if (jwt && jwt !== SUPABASE_ANON_KEY) {
      const admin = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "");
      const { data } = await admin.auth.getUser(jwt);
      userId = data?.user?.id ?? null;
    }
  }

  const supabase = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "");
  const { error } = await supabase.from("device_tokens").upsert(
    {
      token,
      user_id: userId,
      app_version: appVersion,
      last_seen_at: new Date().toISOString(),
    },
    { onConflict: "token" },
  );

  if (error) return json({ error: error.message }, 500);
  return json({ registered: true, linked_account: userId !== null });
});
