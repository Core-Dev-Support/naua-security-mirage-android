export function parseDate(value: string | null): number {
  if (!value) return 0;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function nextPaidUntil(
  previous: string | null,
  days = 30,
  now = Date.now(),
): string {
  const start = Math.max(now, parseDate(previous));
  return new Date(start + days * 24 * 60 * 60 * 1000).toISOString();
}

export function isRubleCurrency(value: string): boolean {
  return ["643", "rub", "rur"].includes(value.trim().toLowerCase());
}

export function isAllowedNotificationType(value: string): boolean {
  return value.trim().toLowerCase() === "payout";
}

export function parseAmountToKopecks(value: string): number | null {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null;
  const [whole, fraction = ""] = normalized.split(".");
  const kopecks = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(kopecks) ? kopecks : null;
}

export function isExpectedAmount(
  value: string,
  expected: string,
  toleranceKopecks = 0,
): boolean {
  const actualKopecks = parseAmountToKopecks(value);
  const expectedKopecks = parseAmountToKopecks(expected);
  if (actualKopecks === null || expectedKopecks === null) return false;
  const tolerance = Number.isFinite(toleranceKopecks) ? Math.max(0, Math.floor(toleranceKopecks)) : 0;
  return Math.abs(actualKopecks - expectedKopecks) <= tolerance;
}

export function makeVlessUrl(
  uuid: string,
  flow: string,
  host: string,
  port: string,
  publicKey: string,
  fingerprint: string,
  serverName: string,
  shortId: string,
  spiderX: string,
): string {
  const query = new URLSearchParams({
    type: "tcp",
    security: "reality",
    pbk: publicKey,
    fp: fingerprint,
    sni: serverName,
    sid: shortId,
    spx: spiderX,
    flow,
  });
  return `vless://${uuid}@${host}:${port}?${query.toString()}#NAUA%20Mirage%20France%20(Premium)`;
}
