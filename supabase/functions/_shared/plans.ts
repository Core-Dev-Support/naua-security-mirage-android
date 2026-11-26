export const REFERRAL_BONUS_DAYS = 2;
export const REFERRAL_MILESTONE_EVERY = 5;
export const REFERRAL_MILESTONE_DAYS = 10;

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
