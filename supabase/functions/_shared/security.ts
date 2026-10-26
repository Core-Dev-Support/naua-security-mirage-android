// Общие помощники для Edge Functions: сравнение секретов и ограничение частоты.
//
// Supabase Edge Functions выполняются в нескольких изолятах, поэтому счётчики
// здесь живут в памяти одного изолята. Это не идеальная защита от распределённого
// флуда, но она отсекает самые дешёвые атаки (цикл запросов с одного адреса) без
// дополнительной таблицы и без задержки на каждый запрос.

export function timingSafeEqual(left: string, right: string): boolean {
  const a = left ?? "";
  const b = right ?? "";
  let mismatch = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i += 1) {
    mismatch |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return mismatch === 0;
}

export function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const first = forwarded.split(",")[0]?.trim();
  if (first) return first;
  return req.headers.get("cf-connecting-ip")?.trim() ||
    req.headers.get("x-real-ip")?.trim() ||
    "unknown";
}

type Bucket = { count: number; resetAt: number };

const buckets = new Map<string, Bucket>();
let lastSweep = 0;

/**
 * Возвращает true, если запрос можно обслужить, и false, если лимит исчерпан.
 * Ключ должен включать имя функции и идентификатор (IP или user id).
 */
export function allowRequest(
  key: string,
  limit: number,
  windowMs: number,
  now = Date.now(),
): boolean {
  if (limit <= 0 || windowMs <= 0) return true;

  if (now - lastSweep > 60_000) {
    lastSweep = now;
    for (const [bucketKey, bucket] of buckets) {
      if (bucket.resetAt <= now) buckets.delete(bucketKey);
    }
  }

  const current = buckets.get(key);
  if (!current || current.resetAt <= now) {
    buckets.set(key, { count: 1, resetAt: now + windowMs });
    return true;
  }
  if (current.count >= limit) return false;
  current.count += 1;
  return true;
}

export function tooManyRequests(retryAfterSeconds = 60): Response {
  return new Response(JSON.stringify({ error: "too many requests" }), {
    status: 429,
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Content-Type": "application/json",
      "Retry-After": String(retryAfterSeconds),
    },
  });
}
