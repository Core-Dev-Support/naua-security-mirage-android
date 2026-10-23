import { PLANS, buildPaymentUrl } from "./plans.ts";

// Тарифы общие для бота и приложения. В вебхуке лежит такой же список
// (supabase/functions/yoomoney-webhook/contract.ts) — кросс-импорт между
// каталогами supabase/functions/* не деплоится, поэтому списки продублированы,
// а эти тесты держат одинаковые значения в обоих местах.
const EXPECTED: Array<[amount: number, days: number]> = [
  [30, 30],
  [80, 90],
  [150, 180],
  [250, 365],
];

Deno.test("the tariff table matches the webhook contract", () => {
  if (PLANS.length !== EXPECTED.length) {
    throw new Error(`expected ${EXPECTED.length} plans, got ${PLANS.length}`);
  }
  PLANS.forEach((plan, index) => {
    const [amount, days] = EXPECTED[index];
    if (plan.amountRub !== amount) {
      throw new Error(`plan ${index}: expected ${amount} ₽, got ${plan.amountRub} ₽`);
    }
    if (plan.days !== days) {
      throw new Error(`plan ${index}: expected ${days} days, got ${plan.days}`);
    }
  });
});

Deno.test("the payment link carries the wallet, the amount and the account id", () => {
  // Номер кошелька в коде не хранится — он приходит из окружения.
  Deno.env.set("YOOMONEY_WALLET", "TEST-WALLET-0000");
  const accountId = "c8bed4ce-6110-495e-be78-9595caed51da";
  for (const plan of PLANS) {
    const parsed = new URL(buildPaymentUrl(plan, accountId, "NAUA VPN"));
    if (parsed.searchParams.get("label") !== accountId) {
      throw new Error("label must be the auth user id");
    }
    if (Number(parsed.searchParams.get("sum")) !== plan.amountRub) {
      throw new Error("sum must match the tariff");
    }
    if (parsed.searchParams.get("receiver") !== "TEST-WALLET-0000") {
      throw new Error("receiver must come from the environment");
    }
    if (!parsed.hostname.endsWith("yoomoney.ru")) {
      throw new Error("the link must point to YooMoney");
    }
  }
});
