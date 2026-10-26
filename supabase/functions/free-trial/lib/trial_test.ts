import {
  TRIAL_DAYS,
  hadSubscriptionBefore,
  trialExpiry,
  trialSubscriptionRow,
} from "./trial.ts";

// Пробный день: новый пользователь получает один день платного узла,
// возвращающийся — ничего. Проверки чистые, чтобы их можно было прогнать
// без базы.

Deno.test("a new user gets a one-day trial", () => {
  const now = "2026-10-22T12:00:00.000Z";
  const row = trialSubscriptionRow({
    userId: "11111111-1111-1111-1111-111111111111",
    email: "new@example.com",
    now,
  });
  if (row.plan !== "trial") throw new Error("the plan must be trial");
  if (row.is_active !== true) throw new Error("the trial must be active");
  const until = Date.parse(String(row.paid_until));
  const days = (until - Date.parse(now)) / 86_400_000;
  if (days !== TRIAL_DAYS) throw new Error(`the trial must be ${TRIAL_DAYS} day(s), got ${days}`);
  if (row.email !== "new@example.com") throw new Error("the email must be written");
});

Deno.test("a returning user has had a subscription before", () => {
  if (hadSubscriptionBefore(null)) throw new Error("no row -> no subscription");
  if (hadSubscriptionBefore({ plan: null, paid_until: null })) {
    throw new Error("an empty row -> no subscription");
  }
  if (!hadSubscriptionBefore({ plan: "premium", paid_until: null })) {
    throw new Error("a plan means there was a subscription");
  }
  if (!hadSubscriptionBefore({ plan: "trial", paid_until: null })) {
    throw new Error("a used trial means there was a subscription");
  }
  if (!hadSubscriptionBefore({ plan: null, paid_until: "2026-10-22T00:00:00Z" })) {
    throw new Error("an expiry means there was a subscription");
  }
});

Deno.test("the trial lasts exactly one day", () => {
  const start = Date.parse("2026-10-22T12:00:00.000Z");
  const until = Date.parse(trialExpiry(start));
  if (until - start !== 86_400_000) throw new Error("the trial must be 24 hours long");
});
