// Оплата подписки в боте: тарифы и ссылка YooMoney.
//
// Тарифы те же, что и в вебхуке (supabase/functions/yoomoney-webhook/contract.ts):
// 30 ₽ -> 30 дней, 80 ₽ -> 90, 150 ₽ -> 180, 250 ₽ -> 365.
// Кросс-импорты между каталогами supabase/functions/* не деплоятся, поэтому
// список продублирован; тесты в обоих местах держат одинаковые значения.
//
// В ссылке оплаты label — это auth.users.id владельца аккаунта: вебхук
// находит плательщика именно по нему и начисляет подписку в общей таблице.

export type Plan = {
  amountRub: number;
  days: number;
  /** Подпись на кнопке, ru. */
  labelRu: string;
  /** Подпись на кнопке, en. */
  labelEn: string;
};

export const PLANS: Plan[] = [
  { amountRub: 30, days: 30, labelRu: "1 месяц", labelEn: "1 month" },
  { amountRub: 80, days: 90, labelRu: "3 месяца", labelEn: "3 months" },
  { amountRub: 150, days: 180, labelRu: "6 месяцев", labelEn: "6 months" },
  { amountRub: 250, days: 365, labelRu: "1 год", labelEn: "1 year" },
];

// envOr — переменная окружения с запасным значением.
//
// В Deno доступ к окружению требует разрешения, поэтому читаем через
// try/catch: модуль должен импортироваться и в тестах без --allow-env.
function envOr(name: string, fallback: string): string {
  try {
    return Deno.env.get(name) || fallback;
  } catch {
    return fallback;
  }
}

// pricePerDay — цена одного дня по тарифу.
export function pricePerDay(plan: Plan): number {
  return plan.amountRub / plan.days;
}

// savingPercent — на сколько процентов тариф дешевле месячного по цене дня.
//
// Считается от самого короткого тарифа: 30 ₽ за 30 дней — это 1 ₽ в день,
// с ним сравниваем остальные.
export function savingPercent(plan: Plan): number {
  const base = pricePerDay(PLANS[0]);
  if (base <= 0) return 0;
  return Math.round((1 - pricePerDay(plan) / base) * 100);
}

// bestSaving — максимальная выгода среди тарифов (0, если выгоды нет).
export function bestSaving(): number {
  return Math.max(...PLANS.map((plan) => savingPercent(plan)));
}

// yoomoneyWallet — номер кошелька из окружения.
//
// В коде держать его нельзя (хук репозитория справедливо блокирует такое),
// поэтому читаем при каждом обращении: так его можно и подменить в тестах.
export function yoomoneyWallet(): string {
  return envOr("YOOMONEY_WALLET", "");
}

// Куда YooMoney возвращает пользователя после оплаты.
export const PAYMENT_SUCCESS_URL = envOr("PAYMENT_SUCCESS_URL", "");

// Ссылка на поддержку NAUA Security Mirage (чат проекта).
export const SUPPORT_URL = envOr(
  "SUPPORT_URL",
  "https://t.me/News_And_Updates_Apps?direct",
);

// Ссылка на канал проекта.
export const CHANNEL_URL = envOr(
  "CHANNEL_URL",
  "https://t.me/News_And_Updates_Apps",
);

// Ссылка на свежие сборки приложения (релизы на GitHub).
export const APP_URL = envOr(
  "APP_URL",
  "https://github.com/Core-Dev-Support/naua-security-mirage-android/releases/latest",
);

// Страница подключения: обычный HTML на GitHub Pages.
//
// Зачем прослойка: Telegram не принимает happ:// в кнопках, а сама ссылка
// подписки отдаётся как text/plain — встроенный браузер скачивает её файлом
// вместо того, чтобы открыть. Страница открывается как сайт и предлагает
// кнопки Happ / Hiddify / v2rayTun: подписка добавляется в один тап.
export const CONNECT_PAGE_URL = envOr(
  "CONNECT_PAGE_URL",
  "https://core-dev-support.github.io/naua-security-mirage-android/",
);

// Лицензионное соглашение и политика конфиденциальности.
//
// Ссылки открыты для всех: ?usp=sharing вместо ?tab=t.0 — иначе документ
// откроется только владельцу.
export const LICENSE_URL = envOr(
  "LICENSE_URL",
  "https://docs.google.com/document/d/1Q0_MpGF5D1GGoFu2HcdTle0kvLb3S7L52x2XIc47eLw/edit?usp=sharing",
);

export const PRIVACY_URL = envOr(
  "PRIVACY_URL",
  "https://docs.google.com/document/d/1FrmDpGS3sC_kQYyv1feNO2G2XMQr_ZV4_GAS-Qm4y1I/edit?usp=sharing",
);

// Способы поблагодарить разработчика — те же, что в приложении.
export const CLOUDTIPS_URL = envOr(
  "CLOUDTIPS_URL",
  "https://pay.cloudtips.ru/p/82445608",
);

export const TRIBUTE_TG_URL = envOr(
  "TRIBUTE_TG_URL",
  "https://t.me/tribute/app?startapp=dS04",
);

export const TRIBUTE_WEB_URL = envOr(
  "TRIBUTE_WEB_URL",
  "https://web.tribute.tg/d/S04",
);

// buildPaymentUrl собирает ссылку быстрой оплаты YooMoney.
//
// label — auth.users.id: по нему вебхук поймёт, кому начислить подписку.
export function buildPaymentUrl(
  plan: Plan,
  authUserId: string,
  purpose: string,
): string {
  const params = new URLSearchParams();
  params.set("receiver", yoomoneyWallet());
  params.set("quickpay-form", "shop");
  params.set("targets", purpose);
  params.set("paymentType", "AC");
  params.set("sum", String(plan.amountRub));
  params.set("label", authUserId);
  if (PAYMENT_SUCCESS_URL) params.set("successURL", PAYMENT_SUCCESS_URL);
  return `https://yoomoney.ru/quickpay/confirm.xml?${params.toString()}`;
}
