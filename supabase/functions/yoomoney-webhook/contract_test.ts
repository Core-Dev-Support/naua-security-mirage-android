import {
  buildSignString,
  isAcceptedTransfer,
  isAllowedNotificationType,
  isExpectedAmount,
  isExpectedPaymentAmount,
  isRubleCurrency,
  makeVlessUrl,
  nextPaidUntil,
  parseDate,
  rfc3986,
} from "./contract.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T): void {
  if (actual !== expected) {
    throw new Error(`expected ${String(expected)}, got ${String(actual)}`);
  }
}

function assertStringIncludes(value: string, expected: string): void {
  assert(value.includes(expected), `expected string to include ${expected}`);
}

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

Deno.test("currency and notification validation reject non-payments", () => {
  assert(isRubleCurrency("643"));
  assert(isRubleCurrency("RUB"));
  assert(!isRubleCurrency("USD"));
  assert(!isRubleCurrency(""));
  assert(isAllowedNotificationType("p2p-incoming"));
  assert(isAllowedNotificationType("card-incoming"));
  assert(isAllowedNotificationType("sbp-incoming"));
  assert(isAllowedNotificationType("  CARD-INCOMING  "));
  assert(!isAllowedNotificationType("payout"));
  assert(!isAllowedNotificationType("refund"));
  assert(!isAllowedNotificationType("chargeback"));
  assert(!isAllowedNotificationType(""));
});

Deno.test("amount validation uses kopecks and rejects underpayment", () => {
  assert(isExpectedAmount("30", "30.00"));
  assert(isExpectedAmount("30.00", "30"));
  assert(!isExpectedAmount("1", "30"));
  assert(!isExpectedAmount("29.90", "30"));
  assert(isExpectedAmount("29.90", "30", 10));
  assert(!isExpectedAmount("not-an-amount", "30"));
});

Deno.test("sign string matches the documented YooMoney example", () => {
  // From the YooMoney notification documentation, where the secret "secret123" produces the sign
  // value in the last row. Getting the ordering or the escaping wrong fails here rather than in
  // production, which is the whole point of pinning it to the published example.
  const entries: Array<[string, string]> = [
    ["notification_type", "p2p-incoming"],
    ["operation_id", "441361714955017004"],
    ["amount", "98.00"],
    ["withdraw_amount", "100.00"],
    ["currency", "643"],
    ["datetime", "2013-12-26T08:28:34Z"],
    ["sender", "41000000000"],
    ["codepro", "false"],
    ["label", "ML23045"],
    ["unaccepted", "false"],
    ["sha1_hash", "ac13833bd6ba9eff1fa9e4bed76f3d6ebb57f6c0"],
    ["sign", "a452af731650e2c5b39abcdc7c28dd27db7b3b654c2230ad2c386e64afb98605"],
  ];
  const expected =
    "amount=98.00&codepro=false&currency=643&datetime=2013-12-26T08%3A28%3A34Z&label=ML23045" +
    "&notification_type=p2p-incoming&operation_id=441361714955017004&sender=41000000000" +
    "&sha1_hash=ac13833bd6ba9eff1fa9e4bed76f3d6ebb57f6c0&unaccepted=false&withdraw_amount=100.00";
  assertEquals(buildSignString(entries), expected);
});

Deno.test("sign string keeps empty values and escapes reserved characters", () => {
  assertEquals(buildSignString([["label", ""], ["a", "1"]]), "a=1&label=");
  assertEquals(rfc3986("a b"), "a%20b");
  assertEquals(rfc3986("!'()*"), "%21%27%28%29%2A");
  assertEquals(rfc3986("plain-._~"), "plain-._~");
});

Deno.test("a held transfer is not accepted", () => {
  assert(isAcceptedTransfer("false"));
  assert(isAcceptedTransfer(null));
  assert(isAcceptedTransfer(""));
  assert(!isAcceptedTransfer("true"));
});

Deno.test("payment amount is accepted gross or net of the commission", () => {
  assert(isExpectedPaymentAmount("30.00", null, "30"));
  assert(isExpectedPaymentAmount("30.00", "0.90", "30"));
  assert(isExpectedPaymentAmount("29.10", "0.90", "30"));
  assert(isExpectedPaymentAmount("29.10", "0,90", "30"));
  assert(!isExpectedPaymentAmount("29.10", null, "30"));
  assert(!isExpectedPaymentAmount("29.10", "0.10", "30"));
  assert(!isExpectedPaymentAmount("29.10", "", "30"));
  assert(!isExpectedPaymentAmount("29.10", "not-a-number", "30"));
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
