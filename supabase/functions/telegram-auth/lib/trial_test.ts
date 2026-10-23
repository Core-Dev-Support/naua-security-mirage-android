import {
  TRIAL_DAYS,
  hadSubscriptionBefore,
  subscriptionRow,
} from "./trial.ts";

// Пробник: новый пользователь получает один день подписки, возвращающийся —
// ничего. Сроки считаем от момента привязки.

Deno.test("a new user gets a one-day trial", () => {
  const now = "2026-10-21T12:00:00.000Z";
  const row = subscriptionRow({
    userId: "11111111-1111-1111-1111-111111111111",
    email: "new@example.com",
    tgId: 42,
    now,
    hadSubscription: false,
  });
  if (row.plan !== "trial") throw new Error("the plan must be trial");
  if (row.is_active !== true) throw new Error("the trial must be active");
  const until = Date.parse(String(row.paid_until));
  const days = (until - Date.parse(now)) / 86_400_000;
  if (days !== TRIAL_DAYS) throw new Error(`the trial must be ${TRIAL_DAYS} day(s), got ${days}`);
});

Deno.test("a returning user keeps their subscription", () => {
  const now = "2026-10-21T12:00:00.000Z";
  const row = subscriptionRow({
    userId: "11111111-1111-1111-1111-111111111111",
    email: "old@example.com",
    tgId: 42,
    now,
    hadSubscription: true,
  });
  if (row.plan !== undefined) throw new Error("the plan must not be touched");
  if (row.paid_until !== undefined) throw new Error("the expiry must not be touched");
  if (row.email !== "old@example.com") throw new Error("the email must be updated");
});

Deno.test("an empty row means the user never had a subscription", () => {
  if (hadSubscriptionBefore(null)) throw new Error("no row -> no subscription");
  if (hadSubscriptionBefore({ plan: null, paid_until: null })) {
    throw new Error("an empty row -> no subscription");
  }
  if (!hadSubscriptionBefore({ plan: "premium", paid_until: null })) {
    throw new Error("a plan means there was a subscription");
  }
  if (!hadSubscriptionBefore({ plan: null, paid_until: "2026-10-22T00:00:00Z" })) {
    throw new Error("an expiry means there was a subscription");
  }
});
