import { PLANS, buildPaymentUrl, parsePlansPayload } from "./plans.ts";

// Тарифы общие для бота и приложения. В вебхуке лежит такой же список
// (supabase/functions/yoomoney-webhook/contract.ts) — кросс-импорт между
// каталогами supabase/functions/* не деплоится, поэтому списки продублированы,
// а эти тесты держат одинаковые значения в обоих местах.
const EXPECTED: Array<[amount: number, days: number]> = [
  [40, 30],
  [110, 90],
  [200, 180],
  [330, 365],
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

Deno.test("the payment link carries the wallet, the amount, the account id and the term", () => {
  // Номер кошелька в коде не хранится — он приходит из окружения.
  Deno.env.set("YOOMONEY_WALLET", "TEST-WALLET-0000");
  const accountId = "c8bed4ce-6110-495e-be78-9595caed51da";
  for (const plan of PLANS) {
    const parsed = new URL(buildPaymentUrl(plan, accountId, "NAUA VPN"));
    if (parsed.searchParams.get("label") !== `${accountId}:${plan.days}`) {
      throw new Error("label must be the auth user id and the term");
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

Deno.test("plans come from app_config and fall back to the built-in list", () => {
  const parsed = parsePlansPayload([
    { days: 30, price_rub: 35, label_ru: "Месяц", label_en: "Month" },
    { days: 365, price_rub: 300, label_ru: "Год", label_en: "Year" },
    { days: 0, price_rub: 10 },
    { days: 90, price_rub: "abc" },
  ]);
  if (parsed.length !== 2) throw new Error(`expected 2 plans, got ${parsed.length}`);
  if (parsed[0].amountRub !== 35 || parsed[0].labelRu !== "Месяц") {
    throw new Error("the first plan must be the 35 ₽ month");
  }
  if (parsed[1].days !== 365 || parsed[1].amountRub !== 300) {
    throw new Error("the second plan must be the 300 ₽ year");
  }
  if (parsePlansPayload(null) !== PLANS) {
    throw new Error("an empty payload must fall back to built-in plans");
  }
});
