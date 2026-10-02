import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const FIREBASE_SERVICE_ACCOUNT = Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "";
const PUSH_SEND_SECRET = Deno.env.get("PUSH_SEND_SECRET") ?? "";

const CHANNEL_ID = "mirage_updates";
const CONCURRENCY = 25;
const MAX_TOKENS = 20000;
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function b64url(input: string | Uint8Array): string {
  const text = typeof input === "string" ? input : Array.from(input, (c) => String.fromCharCode(c)).join("");
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): Uint8Array {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const binary = atob(base64);
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) der[i] = binary.charCodeAt(i);
  return der;
}

function versionParts(value: string): number[] {
  return value
    .trim()
    .replace(/^[vV]/, "")
    .split(".")
    .map((part) => Number.parseInt(part, 10))
    .map((n) => (Number.isFinite(n) ? n : 0));
}

function isOlder(current: string, target: string): boolean {
  const a = versionParts(current);
  const b = versionParts(target);
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i++) {
    const left = a[i] ?? 0;
    const right = b[i] ?? 0;
    if (left < right) return true;
    if (left > right) return false;
  }
  return false;
}

async function getAccessToken(): Promise<string> {
  const account = JSON.parse(FIREBASE_SERVICE_ACCOUNT);
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const issuedAt = Math.floor(Date.now() / 1000);
  const assertion = [
    b64url(JSON.stringify({ alg: "RS256", typ: "JWT" })),
    b64url(
      JSON.stringify({
        iss: account.client_email,
        scope: SCOPE,
        aud: "https://oauth2.googleapis.com/token",
        iat: issuedAt,
        exp: issuedAt + 3600,
      }),
    ),
  ].join(".");

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(assertion),
  );
  const signed = `${assertion}.${b64url(new Uint8Array(signature))}`;

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: signed,
    }),
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`oauth2 responded ${response.status}: ${detail.slice(0, 200)}`);
  }
  const payload = await response.json();
  return payload.access_token as string;
}

serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const provided = req.headers.get("x-push-secret") ?? "";
  if (!PUSH_SEND_SECRET || provided.length !== PUSH_SEND_SECRET.length) {
    return json({ error: "forbidden" }, 403);
  }
  let mismatch = 0;
  for (let i = 0; i < PUSH_SEND_SECRET.length; i++) {
    mismatch |= PUSH_SEND_SECRET.charCodeAt(i) ^ provided.charCodeAt(i);
  }
  if (mismatch !== 0) return json({ error: "forbidden" }, 403);

  if (!FIREBASE_SERVICE_ACCOUNT) return json({ error: "firebase is not configured" }, 500);

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const tag = typeof payload.tag === "string" ? payload.tag.trim() : "";
  const title = typeof payload.title === "string" ? payload.title : "";
  const body = typeof payload.body === "string" ? payload.body : "";
  if (!tag) return json({ error: "tag is required" }, 400);
  if (!title || !body) return json({ error: "title and body are required" }, 400);

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
  const { data: rows, error } = await supabase
    .from("device_tokens")
    .select("token, app_version")
    .limit(MAX_TOKENS);

  if (error) return json({ error: error.message }, 500);

  const targets = (rows ?? []).filter((row) => {
    const version = typeof row.app_version === "string" ? row.app_version : "";
    if (!version) return true;
    return isOlder(version, tag);
  });

  if (targets.length === 0) {
    return json({ sent: 0, skipped: (rows ?? []).length, failed: 0, removed: 0, tag });
  }

  const accessToken = await getAccessToken();
  const endpoint = `https://fcm.googleapis.com/v1/projects/${JSON.parse(FIREBASE_SERVICE_ACCOUNT).project_id}/messages:send`;

  let sent = 0;
  let failed = 0;
  const dead: string[] = [];

  for (let offset = 0; offset < targets.length; offset += CONCURRENCY) {
    const batch = targets.slice(offset, offset + CONCURRENCY);
    const outcomes = await Promise.all(
      batch.map(async (row) => {
        try {
          const response = await fetch(endpoint, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${accessToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              message: {
                token: row.token,
                notification: { title, body },
                data: { tag },
                android: {
                  priority: "high",
                  notification: { channel_id: CHANNEL_ID },
                },
              },
            }),
          });
          if (response.ok) return { token: row.token, ok: true, status: "" };
          const detail = await response.json().catch(() => ({}));
          return { token: row.token, ok: false, status: String(detail?.error?.status ?? "") };
        } catch {
          return { token: row.token, ok: false, status: "TRANSPORT" };
        }
      }),
    );

    for (const outcome of outcomes) {
      if (outcome.ok) {
        sent += 1;
        continue;
      }
      failed += 1;
      if (
        outcome.status === "NOT_FOUND" ||
        outcome.status === "UNREGISTERED" ||
        outcome.status === "INVALID_ARGUMENT"
      ) {
        dead.push(outcome.token);
      }
    }
  }

  if (dead.length > 0) {
    await supabase.from("device_tokens").delete().in("token", dead.slice(0, 1000));
  }

  return json({
    sent,
    failed,
    removed: dead.length,
    skipped: (rows ?? []).length - targets.length,
    tag,
  });
});
