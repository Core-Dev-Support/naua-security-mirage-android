import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const GITHUB_REPO = Deno.env.get("GITHUB_REPO") ?? "Core-Dev-Support/naua-security-mirage-android";
const GITHUB_API_URL = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;
const CACHE_MINUTES = Number(Deno.env.get("RELEASE_CACHE_MINUTES") || "30");
const GITHUB_TOKEN = Deno.env.get("GITHUB_TOKEN") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Cache-Control": "public, max-age=300",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function toRelease(doc: Record<string, unknown>): Record<string, unknown> {
  return {
    tag_name: doc.tag_name ?? "",
    name: doc.name ?? "",
    body: doc.body ?? "",
    html_url: doc.html_url ?? `https://github.com/${GITHUB_REPO}/releases/latest`,
    published_at: doc.published_at ?? null,
    assets: Array.isArray(doc.assets)
      ? (doc.assets as Array<Record<string, unknown>>).map((a) => ({
        name: a.name ?? "",
        browser_download_url: a.browser_download_url ?? "",
        size: a.size ?? 0,
        download_count: a.download_count ?? 0,
        updated_at: a.updated_at ?? null,
      }))
      : [],
  };
}

async function fetchFromGitHub(): Promise<Record<string, unknown> | null> {
  const headers: Record<string, string> = {
    Accept: "application/vnd.github+json",
    "User-Agent": "NAUA-Security-Mirage-release-cache",
  };
  if (GITHUB_TOKEN) headers.Authorization = `Bearer ${GITHUB_TOKEN}`;

  const response = await fetch(GITHUB_API_URL, { headers });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`GitHub responded ${response.status}`);
  const doc = await response.json();
  return toRelease(doc as Record<string, unknown>);
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    return json({ error: "service configuration is incomplete" }, 500);
  }
  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  const { data: cached } = await supabase
    .from("release_cache")
    .select("payload, fetched_at")
    .eq("id", 1)
    .maybeSingle();

  if (cached) {
    const ageMinutes = (Date.now() - Date.parse(String(cached.fetched_at))) / 60000;
    if (Number.isFinite(ageMinutes) && ageMinutes < CACHE_MINUTES) {
      return json({
        ...(cached.payload as Record<string, unknown>),
        cached: true,
        age_minutes: Math.round(ageMinutes),
      });
    }
  }

  try {
    const release = await fetchFromGitHub();
    if (!release) return json({ error: "no release published" }, 404);
    await supabase.from("release_cache").upsert({
      id: 1,
      payload: release,
      fetched_at: new Date().toISOString(),
    });
    return json({ ...release, cached: false, age_minutes: 0 });
  } catch (error) {
    if (cached) {
      return json({
        ...(cached.payload as Record<string, unknown>),
        cached: true,
        stale: true,
        age_minutes: Math.round((Date.now() - Date.parse(String(cached.fetched_at))) / 60000),
      });
    }
    return json({ error: error instanceof Error ? error.message : "upstream failed" }, 502);
  }
});
