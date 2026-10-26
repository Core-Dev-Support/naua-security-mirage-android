import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { allowRequest, clientIp } from "../_shared/security.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const TOKEN_PATTERN = /^[A-Za-z0-9_:.-]{8,512}$/;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function tooMany(): Response {
  return new Response(JSON.stringify({ error: "too many requests" }), {
    status: 429,
    headers: { ...CORS, "Content-Type": "application/json", "Retry-After": "60" },
  });
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const ip = clientIp(req);
  if (!allowRequest(`register-device:ip:${ip}`, 30, 60_000)) return tooMany();

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return json({ error: "invalid json" }, 400);
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const token = typeof body.token === "string" ? body.token.trim() : "";
  const appVersion = typeof body.app_version === "string"
    ? body.app_version.trim().slice(0, 64)
    : null;
  if (!token) return json({ error: "token is required" }, 400);
  if (!TOKEN_PATTERN.test(token)) return json({ error: "token has an invalid format" }, 400);

  // Анонимный вызов разрешён: FCM-токен регистрируется сразу после установки,
  // ещё до входа в аккаунт. Но такой вызов не может ни привязать токен к
  // чужому user_id, ни отвязать уже привязанный.
  const auth = req.headers.get("Authorization") ?? "";
  let userId: string | null = null;
  if (auth.startsWith("Bearer ")) {
    const jwt = auth.slice(7).trim();
    if (jwt && jwt !== SUPABASE_ANON_KEY) {
      if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
        return json({ error: "server is not configured" }, 500);
      }
      const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
        auth: { persistSession: false, autoRefreshToken: false },
      });
      const { data, error } = await userClient.auth.getUser(jwt);
      // Просроченный токен не должен мешать регистрации push: просто
      // оставляем устройство без привязки к аккаунту. Чужой user_id
      // анонимный путь всё равно выставить не может.
      if (!error && data?.user?.id) {
        userId = data.user.id;
        if (!allowRequest(`register-device:user:${userId}`, 10, 60_000)) return tooMany();
      }
    }
  }

  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    return json({ error: "server is not configured" }, 500);
  }
  const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const metadata = {
    app_version: appVersion,
    last_seen_at: new Date().toISOString(),
  };

  if (userId) {
    const { error } = await service.from("device_tokens").upsert(
      { token, user_id: userId, ...metadata },
      { onConflict: "token" },
    );
    if (error) return json({ error: "could not register token" }, 500);
    return json({ registered: true, linked_account: true });
  }

  // insert + обновление только «ничейной» строки. Upsert здесь нельзя:
  // он бы затёр user_id у токена, уже привязанного к аккаунту.
  const { error: insertError } = await service.from("device_tokens").insert({
    token,
    user_id: null,
    ...metadata,
  });
  if (!insertError) return json({ registered: true, linked_account: false });

  if (insertError.code !== "23505") {
    return json({ error: "could not register token" }, 500);
  }

  const { error: updateError } = await service
    .from("device_tokens")
    .update(metadata)
    .eq("token", token)
    .is("user_id", null);
  if (updateError) return json({ error: "could not register token" }, 500);

  return json({ registered: true, linked_account: false });
});
