// Правила реферальной программы.
//
// Те же значения, что в supabase/functions/yoomoney-webhook/contract.ts:
// кросс-импорты между каталогами supabase/functions/* не деплоятся, поэтому
// числа продублированы, а тесты (lib/referral_test.ts) держат их одинаковыми.

// Сколько дней получает пригласивший, когда его реферал оплатил.
export const REFERRAL_BONUS_DAYS = 2;

// Каждые столько оплативших рефералов — ещё REFERRAL_MILESTONE_DAYS дней.
export const REFERRAL_MILESTONE_EVERY = 5;
export const REFERRAL_MILESTONE_DAYS = 10;

// Сколько друзей осталось до следующего дополнительного бонуса.
export function friendsToMilestone(paidReferrals: number): number {
  if (paidReferrals < 0) return REFERRAL_MILESTONE_EVERY;
  const rest = paidReferrals % REFERRAL_MILESTONE_EVERY;
  return rest === 0 ? REFERRAL_MILESTONE_EVERY : REFERRAL_MILESTONE_EVERY - rest;
}
