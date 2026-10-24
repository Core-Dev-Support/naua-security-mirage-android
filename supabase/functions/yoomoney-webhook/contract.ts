// Реферальная программа.
//
// Каждый пригласивший получает REFERRAL_BONUS_DAYS за того, кто пришёл по его
// ссылке и оплатил. Каждые REFERRAL_MILESTONE_EVERY таких рефералов — вдобавок
// REFERRAL_MILESTONE_DAYS.
export const REFERRAL_BONUS_DAYS = 2;
export const REFERRAL_MILESTONE_EVERY = 5;
export const REFERRAL_MILESTONE_DAYS = 10;

// Тарифы подписки NAUA.
//
// Источник — таблица app_config (строка subscription_plans), её читают бот,
// приложение и этот вебхук. Список ниже — только запасной вариант на случай
// недоступности базы, чтобы приём оплаты не останавливался.
//
//   40 ₽  ->  30 дней
//   110 ₽ ->  90 дней
//   200 ₽ -> 180 дней
//   330 ₽ -> 365 дней

export type Plan = {
  amountRub: number;
  days: number;
};

export const PLANS: Plan[] = [
  { amountRub: 40, days: 30 },
  { amountRub: 110, days: 90 },
  { amountRub: 200, days: 180 },
  { amountRub: 330, days: 365 },
];

/**
 * Parses the payment label written by the app and the bot.
 *
 * New links carry both the payer id and the chosen term: `<uuid>:<days>`.
 * Links issued before the server-driven plans used only the uuid, and are
 * still accepted so an already-open payment page keeps working.
 */
export function parsePaymentLabel(label: string): {
  userId: string;
  days: number | null;
} {
  const [rawUserId, rawDays] = label.trim().split(":");
  const userId = (rawUserId || "").trim();
  const days = /^\d{1,4}$/.test((rawDays || "").trim())
    ? Number(rawDays)
    : null;
  return { userId, days: days && days > 0 ? days : null };
}

// Сколько дней начислить за оплаченную сумму.
//
// YooMoney присылает amount (за вычетом комиссии) и commission отдельно, и
// какая из двух величин «правильная» — не угадать. Поэтому проверяются оба
// прочтения: и сама сумма, и сумма вместе с комиссией.
//
// Возвращает null, если сумма не похожа ни на один тариф.
export function planDaysForPayment(
  charged: string,
  commission: string | null,
  requestedDays: number | null = null,
  plans: Plan[] = PLANS,
): number | null {
  const chargedKopecks = parseAmountToKopecks(charged);
  if (chargedKopecks === null) return null;

  const feeKopecks = commission ? parseAmountToKopecks(commission) : null;
  const candidates = [chargedKopecks];
  if (feeKopecks !== null) candidates.push(chargedKopecks + feeKopecks);

  // The term in the label selects the plan, but the amount must still match
  // that plan. Otherwise a payer could label a 40 ₽ transfer as a year.
  const wantedDays = requestedDays && Number.isInteger(requestedDays) && requestedDays > 0
    ? requestedDays
    : null;

  for (const kopecks of candidates) {
    const rub = kopecks / 100;
    for (const plan of plans) {
      if (wantedDays !== null && plan.days !== wantedDays) continue;
      // Полурубля допускаем: YooMoney иногда присылает сумму с копейками.
      if (Math.abs(rub - plan.amountRub) < 0.005) return plan.days;
    }
  }
  return null;
}

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

// RFC 3986 leaves the sub-delims and the asterisk alone, but YooMoney's signature covers the
// percent-encoded form, so those five have to be escaped as well. encodeURIComponent does not do it.
export function rfc3986(value: string): string {
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase(),
  );
}

// Builds the string YooMoney signs: every notification parameter except "sign", ordered by key,
// values percent-encoded, joined with "&", empty values kept as "key=".
export function buildSignString(entries: Array<[string, string]>): string {
  const pairs = entries.filter(([key]) => key !== "sign");
  pairs.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return pairs.map(([key, value]) => `${key}=${rfc3986(value)}`).join("&");
}

const INCOMING_NOTIFICATION_TYPES = new Set([
  "p2p-incoming",
  "card-incoming",
  "sbp-incoming",
]);

export function isAllowedNotificationType(value: string): boolean {
  return INCOMING_NOTIFICATION_TYPES.has(value.trim().toLowerCase());
}

// A transfer can be flagged as held, meaning the money is frozen until the payer frees up balance in
// their wallet. The documentation says the flag is always false now, but it exists precisely so a
// held transfer is not mistaken for a settled one.
export function isAcceptedTransfer(flag: string | null): boolean {
  return !flag || flag.trim().toLowerCase() === "false";
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

// YooMoney reports what was charged as "amount" and its own cut separately as "commission", so the
// amount that lands in the merchant balance is lower than what the customer paid. Which of the two
// conventions a given notification uses is not something to guess at: a paid customer whose payment
// is refused on a 90 kopeck difference keeps their money and gets nothing. Both readings are tried
// instead. The notification is signature-checked before this runs, so widening the accepted values
// does not let anyone forge a payment.
export function isExpectedPaymentAmount(
  value: string,
  commission: string | null,
  expected: string,
  toleranceKopecks = 0,
): boolean {
  if (isExpectedAmount(value, expected, toleranceKopecks)) return true;
  if (!commission || !commission.trim()) return false;
  const paid = parseAmountToKopecks(value);
  const fee = parseAmountToKopecks(commission);
  if (paid === null || fee === null) return false;
  return isExpectedAmount(((paid + fee) / 100).toFixed(2), expected, toleranceKopecks);
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
  return `vless://${uuid}@${host}:${port}?${query.toString()}#${
    encodeURIComponent("NAUA Security Mirage Франция (Платный)")
  }`;
}
