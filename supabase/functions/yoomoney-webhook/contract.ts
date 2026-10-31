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
//   50 ₽  ->  30 дней
//   140 ₽ ->  90 дней
//   250 ₽ -> 180 дней
//   410 ₽ -> 365 дней

export type Plan = {
  amountRub: number;
  days: number;
};

export const PLANS: Plan[] = [
  { amountRub: 50, days: 30 },
  { amountRub: 140, days: 90 },
  { amountRub: 250, days: 180 },
  { amountRub: 410, days: 365 },
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
  // that plan. Otherwise a payer could label a 50 ₽ transfer as a year.
  const wantedDays = requestedDays && Number.isInteger(requestedDays) && requestedDays > 0
    ? requestedDays
    : null;

  for (const kopecks of candidates) {
    const rub = kopecks / 100;
    for (const plan of plans) {
      if (wantedDays !== null && plan.days !== wantedDays) continue;
      // Допуск в полкопейки: YooMoney отдаёт суммы с округлением.
      if (Math.abs(rub - plan.amountRub) < 0.005) return plan.days;
    }
  }

  // Старые APK (1.3.0) не присылают срок и платят фиксированные 30 ₽. Чтобы
  // не брать деньги и ничего не начислять, считаем срок пропорционально
  // самому дешёвому тарифу: 30 ₽ при 50 ₽/30 дней → 18 дней.
  if (wantedDays === null && plans.length > 0) {
    const cheapest = plans.reduce((a, b) => (b.amountRub < a.amountRub ? b : a));
    if (cheapest.amountRub > 0 && cheapest.days > 0) {
      const paidKopecks = Math.max(...candidates);
      const days = Math.floor((paidKopecks / 100 / cheapest.amountRub) * cheapest.days);
      if (days >= 1) return Math.min(days, 3650);
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


