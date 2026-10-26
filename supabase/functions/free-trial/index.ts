import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { allowRequest, clientIp } from "../_shared/security.ts";
import {
  hadSubscriptionBefore,
  trialSubscriptionRow,
} from "./lib/trial.ts";

// Пробный день для платного узла.
//
// Клиент (приложение) приходит с сессией Supabase и ключом устройства.
// Сервер решает, можно ли выдать пробник, и сам записывает его в подписку:
// клиент не может ни выдать, ни продлить подписку сам (RLS это запрещает).
//
// Ответ: 200 — пробник выдан, 409 — пробник уже был, 400 — плохой запрос,
// 401 — нет сессии.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

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
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  try {
    if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Настройки Supabase заданы не полностью");
    }

    const ip = clientIp(req);
    if (!allowRequest(`free-trial:ip:${ip}`, 10, 60_000)) {
      return json({ error: "Слишком много запросов, попробуйте позже" }, 429);
    }

    const token = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
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
    if (!allowRequest(`free-trial:user:${userId}`, 3, 60_000)) {
      return json({ error: "Слишком много запросов, попробуйте позже" }, 429);
    }

    let deviceKey = "";
    try {
      const body = await req.json();
      deviceKey = String(body?.device_key || "").trim().toLowerCase();
    } catch {
      deviceKey = "";
    }
    // Приложение присылает SHA-256 отпечаток устройства. Свободная строка
    // позволяла бы перебирать «устройства» и получать пробник многократно.
    if (!/^[a-f0-9]{64}$/.test(deviceKey)) {
      return json({ error: "Некорректный ключ устройства" }, 400);
    }

    const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    // Пробник был? Смотрим строку подписки: план или срок означают, что
    // подписка уже была — тогда пробник не выдаём.
    const { data: rows, error: subError } = await service
      .from("subscriptions")
      .select("user_id,plan,paid_until")
      .eq("user_id", userId)
      .limit(1);
    if (subError) throw new Error(`Subscription lookup failed: ${subError.message}`);

    const previous = (rows && rows[0]) as
      | { user_id: string; plan: string | null; paid_until: string | null }
      | undefined;
    if (hadSubscriptionBefore(previous)) {
      return json({ error: "Пробный день уже использован" }, 409);
    }

    // Пробник на этом устройстве уже был? Запись в trial_devices с уникальным
    // ключом: повторный запрос отклоняет сама база, гонки тоже.
    const { error: deviceError } = await service
      .from("trial_devices")
      .insert({ device_key: deviceKey, user_id: userId });
    if (deviceError) {
      if (deviceError.code === "23505") {
        return json({ error: "Пробный день на этом устройстве уже использован" }, 409);
      }
      throw new Error(`Не удалось записать устройство: ${deviceError.message}`);
    }

    const now = new Date().toISOString();
    const row = trialSubscriptionRow({ userId, email, now });
    const { error: upsertError } = await service
      .from("subscriptions")
      .upsert(row, { onConflict: "user_id" });
    if (upsertError) throw new Error(`Subscription upsert failed: ${upsertError.message}`);

    console.log("free trial granted", { user: userId.slice(0, 8), device: deviceKey.slice(0, 8) });
    return json({
      ok: true,
      plan: row.plan,
      paidUntil: row.paid_until,
      days: 1,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Внутренняя ошибка";
    console.error("free-trial failed:", message);
    return json({ error: "Не удалось выдать пробный день" }, 500);
  }
});
