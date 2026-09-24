import { assert, assertEquals, assertStringIncludes } from "https://deno.land/std@0.168.0/assert/mod.ts";
import { isRubleCurrency, makeVlessUrl, nextPaidUntil, parseDate } from "./contract.ts";

Deno.test("subscription renewal extends an unexpired entitlement", () => {
  const now = Date.parse("2026-09-24T00:00:00.000Z");
  const previous = "2026-10-01T00:00:00.000Z";
  assertEquals(nextPaidUntil(previous, 30, now), "2026-10-31T00:00:00.000Z");
});

Deno.test("subscription renewal starts from now when previous is expired", () => {
  const now = Date.parse("2026-09-24T00:00:00.000Z");
  assertEquals(nextPaidUntil("2020-01-01T00:00:00.000Z", 30, now), "2026-10-24T00:00:00.000Z");
  assertEquals(parseDate("not-a-date"), 0);
});

Deno.test("currency validation accepts YooMoney RUB representations", () => {
  assert(isRubleCurrency("643"));
  assert(isRubleCurrency("RUB"));
  assert(!isRubleCurrency("USD"));
});

Deno.test("VLESS URL keeps Reality and flow parameters", () => {
  const link = makeVlessUrl(
    "11111111-1111-1111-1111-111111111111",
    "xtls-rprx-vision",
    "example.org",
    "443",
    "public-key",
    "chrome",
    "cdn.example",
    "abcd",
    "/",
  );
  assertStringIncludes(link, "type=tcp");
  assertStringIncludes(link, "security=reality");
  assertStringIncludes(link, "sni=cdn.example");
  assertStringIncludes(link, "sid=abcd");
  assertStringIncludes(link, "flow=xtls-rprx-vision");
});
