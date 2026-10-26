import { assertEquals } from "https://deno.land/std@0.168.0/testing/asserts.ts";
import { allowRequest, clientIp, timingSafeEqual } from "./security.ts";

Deno.test("timingSafeEqual accepts only an exact match", () => {
  assertEquals(timingSafeEqual("secret", "secret"), true);
  assertEquals(timingSafeEqual("secret", "secreT"), false);
  assertEquals(timingSafeEqual("secret", "secret "), false);
  assertEquals(timingSafeEqual("", ""), true);
});

Deno.test("allowRequest stops after the limit and resets after the window", () => {
  const key = `test:${crypto.randomUUID()}`;
  assertEquals(allowRequest(key, 2, 1000, 0), true);
  assertEquals(allowRequest(key, 2, 1000, 100), true);
  assertEquals(allowRequest(key, 2, 1000, 200), false);
  assertEquals(allowRequest(key, 2, 1000, 1201), true);
});

Deno.test("clientIp takes the first forwarded address", () => {
  const req = new Request("https://example.com", {
    headers: { "x-forwarded-for": "203.0.113.5, 10.0.0.1" },
  });
  assertEquals(clientIp(req), "203.0.113.5");
});
