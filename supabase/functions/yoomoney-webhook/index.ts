import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { crypto } from "https://deno.land/std@0.168.0/crypto/mod.ts";
import { isRubleCurrency, makeVlessUrl, nextPaidUntil } from "./contract.ts";

type SubscriptionRow = {
  user_id: string;
  email: string | null;
  is_active: boolean;
  plan: string;
  paid_until: string | null;
  vless_key: string | null;
  client_uuid: string | null;
  flow: string | null;
  operation_id: string | null;
};

type ThreeXClient = {
  id: string;
  email: string;
  flow: string;
  enable: boolean;
  expiryTime: number;
};

const THREE_X_UI_BASE_URL = Deno.env.get("THREE_X_UI_BASE_URL") || "";
const THREE_X_UI_USER = Deno.env.get("THREE_X_UI_USERNAME") || Deno.env.get("THREE_X_UI_USER") || "";
const THREE_X_UI_PASS = Deno.env.get("THREE_X_UI_PASSWORD") || Deno.env.get("THREE_X_UI_PASS") || "";
const THREE_X_UI_INBOUND_ID = Number(Deno.env.get("THREE_X_UI_INBOUND_ID") || "2");
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const YOOMONEY_SECRET = Deno.env.get("YOOMONEY_SECRET") || "";
const FRANCE_HOST = Deno.env.get("FRANCE_HOST") || "";
const FRANCE_PORT = Deno.env.get("FRANCE_PORT") || "443";
const FRANCE_PBK = Deno.env.get("FRANCE_PBK") || "";
const FRANCE_SNI = Deno.env.get("FRANCE_SNI") || "";
const FRANCE_SID = Deno.env.get("FRANCE_SID") || "";
const FRANCE_SPX = Deno.env.get("FRANCE_SPX") || "/";
const FRANCE_FINGERPRINT = Deno.env.get("FRANCE_FINGERPRINT") || "chrome";

function text(formData: FormData, key: string): string {
  return formData.get(key)?.toString().trim() || "";
}

function assert3xSuccess(response: Response, body: string, operation: string): void {
  if (!response.ok) {
    throw new Error(`${operation} failed with HTTP ${response.status}`);
  }
  if (!body) return;
  try {
    const parsed = JSON.parse(body);
    if (parsed && parsed.success === false) {
      throw new Error(`${operation} was rejected by 3X-UI`);
    }
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`${operation} returned invalid JSON`);
    }
    throw error;
  }
}

async function verifyYooMoneySignature(
  notificationType: string,
  operationId: string,
  amount: string,
  currency: string,
  datetime: string,
  sender: string,
  codepro: string,
  label: string,
  receivedHash: string,
): Promise<void> {
  if (!YOOMONEY_SECRET) {
    throw new Error("YOOMONEY_SECRET is not configured");
  }
  if (!receivedHash) {
    throw new Error(" YooMoney signature is missing");
  }
  const checkString = `${notificationType}&${operationId}&${amount}&${currency}&${datetime}&${sender}&${codepro}&${YOOMONEY_SECRET}&${label}`;
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(checkString));
  const calculated = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
  if (calculated.toLowerCase() !== receivedHash.toLowerCase()) {
    throw new Error(" YooMoney signature mismatch");
  }
}

function vlessUrl(uuid: string, flow: string): string {
  return makeVlessUrl(
    uuid,
    flow,
    FRANCE_HOST,
    FRANCE_PORT,
    FRANCE_PBK,
    FRANCE_FINGERPRINT,
    FRANCE_SNI,
    FRANCE_SID,
    FRANCE_SPX,
  );
}

async function getInboundClient(cookie: string, userId: string, email: string, clientUuid: string | null): Promise<ThreeXClient | null> {
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/get/${THREE_X_UI_INBOUND_ID}`, {
    headers: { Cookie: cookie },
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI inbound lookup");
  const root = JSON.parse(body);
  const settings = JSON.parse(root.obj?.settings || "{}");
  const clients: ThreeXClient[] = Array.isArray(settings.clients) ? settings.clients : [];
  return clients.find((client) => {
    const matches = (clientUuid && client.id.toLowerCase() === clientUuid.toLowerCase()) ||
      client.email?.toLowerCase() === email.toLowerCase() ||
      client.email?.toLowerCase() === `user_${userId.substring(0, 8).toLowerCase()}@mirage.app`;
    return matches && client.enable !== false;
  }) || null;
}

async function loginTo3xUi(): Promise<string> {
  if (!THREE_X_UI_BASE_URL || !THREE_X_UI_USER || !THREE_X_UI_PASS) {
    throw new Error("3X-UI configuration is incomplete");
  }
  const body = new URLSearchParams({ username: THREE_X_UI_USER, password: THREE_X_UI_PASS });
  const response = await fetch(`${THREE_X_UI_BASE_URL}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const responseBody = await response.text();
  assert3xSuccess(response, responseBody, "3X-UI login");
  const cookie = response.headers.get("set-cookie")?.split(";")[0] || "";
  if (!cookie) throw new Error("3X-UI login did not return a session cookie");
  return cookie;
}

async function ensure3xClient(
  cookie: string,
  userId: string,
  email: string,
  existing: SubscriptionRow | null,
  expiryMs: number,
): Promise<{ uuid: string; flow: string }> {
  const existingClient = await getInboundClient(
    cookie,
    userId,
    email,
    existing?.client_uuid || null,
  );
  if (existingClient && (existingClient.expiryTime === 0 || existingClient.expiryTime > expiryMs)) {
    return { uuid: existingClient.id, flow: existingClient.flow || "xtls-rprx-vision" };
  }

  // If the old client is expired, never submit the same UUID to addClient:
  // 3X-UI treats that as a duplicate. Keep the old row for audit and issue a
  // new client for the renewal.
  const uuid = existingClient ? crypto.randomUUID() : (existing?.client_uuid || crypto.randomUUID());
  const flow = existingClient?.flow || existing?.flow || "xtls-rprx-vision";
  const client = {
    id: uuid,
    email,
    flow,
    limitIp: 0,
    totalGB: 0,
    expiryTime: expiryMs,
    enable: true,
    tgId: "",
    subId: uuid.replace(/-/g, "").slice(0, 16),
  };
  const settings = JSON.stringify({ clients: [client] });
  const response = await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/addClient`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Cookie: cookie },
    body: JSON.stringify({ id: THREE_X_UI_INBOUND_ID, settings }),
  });
  const body = await response.text();
  assert3xSuccess(response, body, "3X-UI addClient");
  return { uuid, flow };
}

serve(async (req) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      throw new Error("Supabase service configuration is incomplete");
    }

    const formData = await req.formData();
    const notificationType = text(formData, "notification_type");
    const operationId = text(formData, "operation_id");
    const amount = text(formData, "amount");
    const currency = text(formData, "currency");
    const datetime = text(formData, "datetime");
    const sender = text(formData, "sender");
    const codepro = text(formData, "codepro");
    const label = text(formData, "label");
    const signature = text(formData, "sha1_hash");

    if (!operationId || !label) return new Response("operation_id and label are required", { status: 400 });
    if (codepro.toLowerCase() !== "true") return new Response("Payment is not completed", { status: 400 });
    if (!amount || Number(amount) <= 0) return new Response("Invalid amount", { status: 400 });
    if (currency && !isRubleCurrency(currency)) {
      return new Response("Unsupported currency", { status: 400 });
    }

    await verifyYooMoneySignature(
      notificationType,
      operationId,
      amount,
      currency,
      datetime,
      sender,
      codepro,
      label,
      signature,
    );

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
    const { data: userData, error: userError } = await supabase.auth.admin.getUserById(label);
    if (userError || !userData.user?.email) {
      return new Response("Unknown payer account", { status: 400 });
    }
    const email = userData.user.email;

    const { data: existingRows, error: existingError } = await supabase
      .from("subscriptions")
      .select("user_id,email,is_active,plan,paid_until,vless_key,client_uuid,flow,operation_id")
      .eq("user_id", label)
      .limit(1);
    if (existingError) throw new Error(`Subscription lookup failed: ${existingError.message}`);

    const existing = (existingRows?.[0] as SubscriptionRow | undefined) || null;
    if (existing?.operation_id === operationId) {
      return new Response("OK - already processed", { status: 200 });
    }

    const paidUntil = nextPaidUntil(existing?.paid_until || null);
    const expiryMs = Date.parse(paidUntil);
    if (!Number.isFinite(expiryMs)) throw new Error("Invalid subscription expiry");

    const cookie = await loginTo3xUi();
    const client = await ensure3xClient(cookie, label, email, existing, expiryMs);
    const key = vlessUrl(client.uuid, client.flow);
    const { error: upsertError } = await supabase
      .from("subscriptions")
      .upsert({
        user_id: label,
        email,
        is_active: true,
        plan: "premium",
        paid_until: paidUntil,
        vless_key: key,
        client_uuid: client.uuid,
        flow: client.flow,
        operation_id: operationId,
        updated_at: new Date().toISOString(),
      }, { onConflict: "user_id" });
    if (upsertError) throw new Error(`Subscription upsert failed: ${upsertError.message}`);

    return new Response("OK", { status: 200 });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Webhook processing error";
    // Never log the YooMoney secret, signature, VLESS key, or service credentials.
    console.error("YooMoney webhook rejected:", message);
    return new Response("Internal error", { status: 500 });
  }
});
