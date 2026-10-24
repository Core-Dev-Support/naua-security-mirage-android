// Тарифы подписки NAUA.
//
// Источник — таблица app_config в Supabase (её же читает приложение).
// Встроенный список ниже нужен только как запасной: если конфиг недоступен,
// бот продолжает продавать по известным ценам, а не остаётся без оплаты.
//
// label ссылки оплаты — `<auth.users.id>:<дней>`: вебхук находит плательщика
// по id и проверяет, что сумма соответствует выбранному сроку.

export type Plan = {
  amountRub: number;
  days: number;
  /** Подпись на кнопке, ru. */
  labelRu: string;
  /** Подпись на кнопке, en. */
  labelEn: string;
};

export const PLANS: Plan[] = [
  { amountRub: 50, days: 30, labelRu: "1 месяц", labelEn: "1 month" },
  { amountRub: 140, days: 90, labelRu: "3 месяца", labelEn: "3 months" },
  { amountRub: 250, days: 180, labelRu: "6 месяцев", labelEn: "6 months" },
  { amountRub: 410, days: 365, labelRu: "1 год", labelEn: "1 year" },
];

// parsePlansPayload — тарифы из строки app_config.subscription_plans.
//
// Пустой или испорченный список означает «используй встроенный»: продавать
// по устаревшей цене лучше, чем не продавать вовсе.
export function parsePlansPayload(payload: unknown): Plan[] {
  if (!Array.isArray(payload)) return PLANS;
  const plans: Plan[] = [];
  for (const raw of payload) {
    if (!raw || typeof raw !== "object") continue;
    const item = raw as Record<string, unknown>;
    const amountRub = Number(item.price_rub);
    const days = Number(item.days);
    if (!Number.isInteger(amountRub) || amountRub <= 0) continue;
    if (!Number.isInteger(days) || days <= 0) continue;
    if (plans.some((plan) => plan.amountRub === amountRub && plan.days === days)) continue;
    const labelRu = typeof item.label_ru === "string" && item.label_ru.trim()
      ? item.label_ru.trim()
      : `${days} дней`;
    const labelEn = typeof item.label_en === "string" && item.label_en.trim()
      ? item.label_en.trim()
      : `${days} days`;
    plans.push({ amountRub, days, labelRu, labelEn });
  }
  if (!plans.length) return PLANS;
  return plans.sort((a, b) => a.days - b.days || a.amountRub - b.amountRub);
}

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

// savingPercent — на сколько процентов тариф дешевле самого короткого.
//
// Считается от тарифа с наименьшим сроком: с ним сравниваем остальные.
export function savingPercent(plan: Plan, plans: Plan[] = PLANS): number {
  const basePlan = plans.reduce(
    (best, item) => (item.days < best.days ? item : best),
    plans[0] ?? plan,
  );
  const base = pricePerDay(basePlan);
  if (base <= 0) return 0;
  return Math.round((1 - pricePerDay(plan) / base) * 100);
}

// bestSaving — максимальная выгода среди тарифов (0, если выгоды нет).
export function bestSaving(plans: Plan[] = PLANS): number {
  return Math.max(...plans.map((plan) => savingPercent(plan, plans)));
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
  // Срок в метке: вебхук проверит и сумму, и срок, поэтому цена может
  // измениться без выпуска новой версии приложения.
  params.set("label", `${authUserId}:${plan.days}`);
  if (PAYMENT_SUCCESS_URL) params.set("successURL", PAYMENT_SUCCESS_URL);
  return `https://yoomoney.ru/quickpay/confirm.xml?${params.toString()}`;
}
