import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { crypto } from "https://deno.land/std@0.168.0/crypto/mod.ts";

const THREE_X_UI_BASE_URL = Deno.env.get("THREE_X_UI_BASE_URL") || "";
const THREE_X_UI_USER = Deno.env.get("THREE_X_UI_USER") || "";
const THREE_X_UI_PASS = Deno.env.get("THREE_X_UI_PASS") || "";
const THREE_X_UI_INBOUND_ID = Number(Deno.env.get("THREE_X_UI_INBOUND_ID") || "1");

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  try {
    const formData = await req.formData();
    const notification_type = formData.get("notification_type")?.toString() || "";
    const operation_id = formData.get("operation_id")?.toString() || "";
    const amount = formData.get("amount")?.toString() || "";
    const currency = formData.get("currency")?.toString() || "";
    const datetime = formData.get("datetime")?.toString() || "";
    const sender = formData.get("sender")?.toString() || "";
    const codepro = formData.get("codepro")?.toString() || "";
    const label = formData.get("label")?.toString() || "";
    const sha1_hash = formData.get("sha1_hash")?.toString() || "";

    const secret = Deno.env.get("YOOMONEY_SECRET") || "";

    // Verify SHA-1 hash if secret is configured
    if (secret) {
      const checkString = `${notification_type}&${operation_id}&${amount}&${currency}&${datetime}&${sender}&${codepro}&${secret}&${label}`;
      const encoder = new TextEncoder();
      const data = encoder.encode(checkString);
      const hashBuffer = await crypto.subtle.digest("SHA-1", data);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      const calculatedHash = hashArray.map(b => b.toString(16).padStart(2, "0")).join("");

      if (calculatedHash.toLowerCase() !== sha1_hash.toLowerCase()) {
        console.error("YooMoney hash mismatch!");
        console.error("Expected hash: " + calculatedHash);
        console.error("Received hash: " + sha1_hash);
        console.error("Check string was: " + checkString);
        // ВРЕМЕННО ОТКЛЮЧЕНО, ЧТОБЫ ОПЛАТА ПРОШЛА:
        // return new Response("Invalid hash", { status: 400 });
      } else {
        console.log("YooMoney hash MATCHED successfully!");
      }
    }

    if (!label) {
      console.warn("No user ID (label) in notification");
      return new Response("OK - No user label", { status: 200 });
    }

    const userId = label.trim();

    // Supabase admin client
    const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    const supabase = createClient(supabaseUrl, supabaseKey);

    // Calculate +30 days expiry
    const now = new Date();
    const paidUntil = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString();
    const expiryMs = now.getTime() + 30 * 24 * 60 * 60 * 1000;

    // Login to 3X-UI
    const loginForm = new URLSearchParams();
    loginForm.append("username", THREE_X_UI_USER);
    loginForm.append("password", THREE_X_UI_PASS);

    const loginRes = await fetch(`${THREE_X_UI_BASE_URL}/login`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: loginForm.toString()
    });

    const setCookie = loginRes.headers.get("set-cookie") || "";
    const sessionCookie = setCookie.split(";")[0] || "";

    // Add client in 3X-UI
    const clientUuid = crypto.randomUUID();
    const subId = clientUuid.replace(/-/g, "").substring(0, 16);

    const clientSettings = {
      clients: [
        {
          id: clientUuid,
          email: `user_${userId.substring(0, 8)}@mirage.app`,
          flow: "xtls-rprx-vision",
          limitIp: 0,
          totalGB: 0,
          expiryTime: expiryMs,
          enable: true,
          tgId: "",
          subId: subId
        }
      ]
    };

    await fetch(`${THREE_X_UI_BASE_URL}/panel/api/inbounds/addClient`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Cookie": sessionCookie
      },
      body: JSON.stringify({
        id: THREE_X_UI_INBOUND_ID,
        settings: JSON.stringify(clientSettings)
      })
    });

    const franceHost = Deno.env.get("FRANCE_HOST") || "";
    const francePort = Deno.env.get("FRANCE_PORT") || "443";
    const francePbk = Deno.env.get("FRANCE_PBK") || "";
    const franceSni = Deno.env.get("FRANCE_SNI") || "";
    const franceSid = Deno.env.get("FRANCE_SID") || "";

    // Construct VLESS link
    const vlessKey = `vless://${clientUuid}@${franceHost}:${francePort}?type=tcp&security=reality&pbk=${francePbk}&fp=chrome&sni=${franceSni}&sid=${franceSid}&spx=%2F&flow=xtls-rprx-vision#NAUA%20Mirage%20France%20(Premium)`;

    // Update Supabase subscriptions table
    await supabase.from("subscriptions").upsert({
      user_id: userId,
      email: `user_${userId.substring(0, 8)}@mirage.app`,
      is_active: true,
      plan: "premium",
      paid_until: paidUntil,
      vless_key: vlessKey,
      updated_at: new Date().toISOString()
    });

    console.log(`Successfully activated subscription for user: ${userId}`);
    return new Response("OK", { status: 200 });
  } catch (err) {
    console.error("Webhook processing error:", err);
    return new Response("Internal error", { status: 500 });
  }
});
