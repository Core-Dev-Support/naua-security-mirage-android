import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import {
  Telegram,
  escapeHtml,
  safeText,
  safeUsername,
  type CallbackQuery,
  type FileRef,
  type InlineButton,
  type InlineKeyboard,
  type TelegramMessage,
  type TelegramUpdate,
} from "./lib/telegram.ts";
import type { BotContext } from "./lib/store.ts";
import {
  BROADCAST_CHUNK,
  addTicketMessage,
  advanceBroadcast,
  broadcastRecipients,
  clearState,
  countRecipients,
  createTicket,
  db as supabase,
  enqueueBroadcast,
  finishBroadcast,
  getAccount,
  getLatestTicket,
  getTicket,
  getScreenId,
  getState,
  getSubscription,
  getUser,
  invalidateContext,
  listBannedUsers,
  listTicketMessages,
  listTickets,
  listUserTickets,
  loadContext,
  logAdmin,
  readBroadcastQueue,
  setConsent,
  setState,
  setLanguage,
  setScreenId,
  setTicketStatus,
  unlinkAccount,
  updateUser,
  upsertUser,
} from "./lib/store.ts";
import { subscriptionSignature, withFreeNodes } from "./lib/subscription.ts";
import { friendsToMilestone } from "./lib/referral.ts";
import {
  APP_URL,
  CLOUDTIPS_URL,
  CONNECT_PAGE_URL,
  LICENSE_URL,
  PLANS,
  PRIVACY_URL,
  SUPPORT_URL,
  TRIBUTE_TG_URL,
  TRIBUTE_WEB_URL,
  bestSaving,
  buildPaymentUrl,
  parsePlansPayload,
  savingPercent,
  yoomoneyWallet,
  type Plan,
} from "./lib/plans.ts";
import {
  LANG_LABEL,
  normalizeLang,
  t,
  type Lang,
} from "./lib/i18n.ts";
import { timingSafeEqual } from "../_shared/security.ts";

// Telegram-бот NAUA на Supabase Edge Functions.
//
// Один аккаунт на Telegram и приложение: вход через Google привязывает
// Telegram-аккаунт к Google-аккаунту (Supabase Auth), а подписка живёт в
// общей таблице subscriptions. Поэтому вход в боте и вход в приложении
// подтягивают одно и то же.
//
// Ссылка подписки собирает узлы с двух разных хостеров: Франция (3X-UI) и
// Беларусь (H1Cloud) — плюс бесплатные обходы.

const TELEGRAM_TOKEN = Deno.env.get("TELEGRAM_TOKEN") || "";
const TELEGRAM_SECRET = Deno.env.get("TELEGRAM_SECRET_TOKEN") || "";
// Метка вебхука: старый тестовый бот шлёт апдейты на тот же URL без метки.
// Такие запросы игнорируем, чтобы он не отвечал через новый токен.
const WEBHOOK_TAG = Deno.env.get("WEBHOOK_TAG") || "";
// Версия документов. Меняется вместе с лицензией/политикой: если у
// пользователя другая версия, бот попросит принять документы заново.
const CONSENT_VERSION = "2026-10-22";
// ADMIN_ID приходит из секретов Supabase. Если он не задан, админка
// выключена: 0 не совпадает ни с одним Telegram ID.
const ADMIN_ID = Number(Deno.env.get("ADMIN_ID") || "0");
const BOT_USERNAME = Deno.env.get("BOT_USERNAME") || "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUBSCRIPTION_SECRET = Deno.env.get("SUBSCRIPTION_SECRET") || "";
const SUB_SECRET = Deno.env.get("SUB_SECRET") || "";
// CRON_KEY — ключ для pg_cron. Отдельный от SUB_SECRET, чтобы можно было
// добавить планировщик, не меняя ключ внешнего крона (если он настроен).
const CRON_KEY = Deno.env.get("CRON_KEY") || "";
const WEBHOOK_URL =
  Deno.env.get("WEBHOOK_URL") || `${SUPABASE_URL}/functions/v1/telegram-bot`;

const telegram = new Telegram(TELEGRAM_TOKEN);

function nowIso(): string {
  return new Date().toISOString();
}

// ===================== Подпись ссылки подписки =====================

// formatDate — компактная дата: 20.10.2026.
export function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) return "";
  const day = String(parsed.getUTCDate()).padStart(2, "0");
  const month = String(parsed.getUTCMonth() + 1).padStart(2, "0");
  return `${day}.${month}.${parsed.getUTCFullYear()}`;
}

// formatAmount — сумма платежа: 50 ₽.
export function formatAmount(value: string | null | undefined): string {
  const num = Number((value ?? "").replace(",", ".").trim());
  if (!Number.isFinite(num)) return "";
  return `${Math.round(num * 100) / 100} ₽`;
}

async function subscriptionLink(tgId: number): Promise<string> {
  const signature = await subscriptionSignature(tgId, SUBSCRIPTION_SECRET);
  return `${SUPABASE_URL}/functions/v1/telegram-subscription?user=${tgId}_${signature}`;
}

// connectPage — ссылка на страницу подключения.
//
// Прямую ссылку подписки в кнопку ставить нельзя: она отдаётся как text/plain,
// и браузер Telegram скачивает её файлом. Страница — обычный HTML, на ней
// кнопки приложений (happ://, hiddify://, v2rayng://): Telegram такие схемы
// в кнопках не принимает, а со страницы они открываются нормально.
async function connectPage(link: string, lang: Lang, tgId: number): Promise<string> {
  const url = new URL(CONNECT_PAGE_URL);
  url.searchParams.set("u", link);
  // Бесплатный узел — только в зашифрованной ссылке Happ, причём ссылка
  // подписана отдельным токеном: дописать ?free=… руками не получится.
  // В обычной подписке (Hiddify, v2rayTun) узла нет.
  const encrypted = await encryptForHapp(
    await withFreeNodes(link, tgId, SUBSCRIPTION_SECRET),
  );
  if (encrypted) url.searchParams.set("h", encrypted);
  url.searchParams.set("lang", lang);
  return url.toString();
}

// encryptForHapp — зашифрованная ссылка happ://crypt5/… .
//
// В Happ у такой ссылки нельзя посмотреть ни адрес подписки, ни конфиги
// серверов. Для бесплатного узла чужого провайдера это обязательно: его
// настройки не должны быть видны пользователю.
const HAPP_CRYPTO_API = Deno.env.get("HAPP_CRYPTO_API") ||
  "https://crypto.happ.su/api-v2.php";
const HAPP_LINK_TTL_MS = 6 * 60 * 60 * 1000;
const happLinks = new Map<string, { link: string; at: number }>();

async function encryptForHapp(link: string): Promise<string | null> {
  const cached = happLinks.get(link);
  if (cached && Date.now() - cached.at < HAPP_LINK_TTL_MS) return cached.link;
  try {
    // Сервис внешний: если он молчит, экран не должен ждать его вечно.
    const response = await fetch(HAPP_CRYPTO_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: link }),
      signal: AbortSignal.timeout(2500),
    });
    if (!response.ok) return null;
    const parsed = await response.json();
    const encrypted = String(parsed?.encrypted_link || "");
    if (!encrypted.startsWith("happ://")) return null;
    happLinks.set(link, { link: encrypted, at: Date.now() });
    return encrypted;
  } catch {
    // Не вышло — страница возьмёт обычную ссылку happ://add/… .
    return null;
  }
}

// ===================== Тексты и клавиатуры =====================

// langOf — язык пользователя (null = ещё не выбран).
function langOf(user: { lang?: string | null } | null): Lang | null {
  return normalizeLang(user?.lang ?? null);
}

// hasConsent — принял ли пользователь текущую версию документов.
function hasConsent(
  user: { consent_version?: string | null } | null,
): boolean {
  return user?.consent_version === CONSENT_VERSION;
}

// languageButtons — выбор языка. Используется и при первом входе, и при
// смене языка. В callback_data зашит экран-источник ("profile" / "main"),
// чтобы после выбора вернуться именно туда, откуда пришли.
// При первом входе источника нет — тогда показываем приветствие.
function languageButtons(
  back: "profile" | "menu" | "welcome" | "settings" | "consent" | null,
): InlineKeyboard {
  const suffix = back ? `|${back}` : "";
  const rows: InlineKeyboard = [[
    { text: LANG_LABEL.ru, callback_data: `lang_ru${suffix}` },
    { text: LANG_LABEL.en, callback_data: `lang_en${suffix}` },
  ]];
  if (back) {
    const backData = back === "profile"
      ? "profile"
      : back === "welcome"
      ? "welcome_back"
      : back === "settings"
      ? "settings"
      : back === "consent"
      ? "consent_back"
      : "back";
    rows.push([{ text: t("ru", "common.back"), callback_data: backData }]);
  }
  return rows;
}

// languageScreenText — текст экрана выбора языка.
//
// Русский и английский блоки идут в ОДНОМ сообщении: тому, кто не читает
// по-русски, нужно понимать, что он выбирает. Два отдельных сообщения
// выглядели бы как два разных экрана. После выбора весь бот говорит
// на выбранном языке.
function languageScreenText(): string {
  return `${t("ru", "lang.title")}\n\n${t("en", "lang.title")}`;
}

// showLanguageSelection — первый экран бота: выбор языка.
//
// Прежний экран заменяется, а не добавляется: в чате должен остаться один
// пузырь бота (иначе после сброса языка висело бы старое меню рядом).
async function showLanguageSelection(chatId: number, msgId = 0): Promise<void> {
  const text = languageScreenText();
  const keyboard = languageButtons(null);
  if (msgId > 0) {
    await renderScreen(chatId, msgId, text, keyboard);
    return;
  }
  await replaceScreen(chatId, text, keyboard);
}

// welcomeText — приветствие. Цитата отвечает на вопрос «что это такое» и
// честно говорит, что бот только начинает и будет улучшаться.
function welcomeText(lang: Lang): string {
  return [
    t(lang, "welcome.title"),
    "",
    `<blockquote>${t(lang, "welcome.quote")}</blockquote>`,
    "",
    t(lang, "welcome.hint"),
  ].join("\n");
}

// showWelcome — приветствие с кнопкой входа через Google.
//
// Меню здесь намеренно НЕ показывается: сначала человек читает, зачем ему
// бот, и видит одну понятную кнопку. Главное меню появляется после входа
// (или после «Пока без входа»).
async function showWelcome(
  chatId: number,
  lang: Lang,
  name: string,
  msgId = 0,
): Promise<void> {
  // Ссылка на вход сразу в кнопке — одного нажатия достаточно.
  // Если экран уже существует (msgId > 0), его id известен и подставляется
  // в ссылку: после входа функция перепишет ИМЕННО этот пузырь.
  // msgId > 0 — экран уже на экране (смена языка): переписываем его.
  // Иначе — /start: удаляем прежний экран и присылаем новый.
  let target = msgId;
  if (target <= 0) {
    target = await replaceScreen(chatId, welcomeText(lang), undefined);
    if (!target) return;
  }

  const loginUrl = await googleLoginUrl(chatId, target);
  const loginButton: InlineButton = loginUrl
    ? { text: t(lang, "welcome.login"), url: loginUrl }
    : { text: t(lang, "welcome.login"), callback_data: "google_login" };

  const keyboard: InlineKeyboard = [
    [loginButton],
    [{ text: t(lang, "profile.language"), callback_data: "lang_menu|welcome" }],
    [{ text: t(lang, "menu.docs"), callback_data: "docs" }],
  ];
  await renderScreen(chatId, target, welcomeText(lang), keyboard);
  void name;
}

// ---------- Согласие с документами ----------

// consentText — документы и согласие. Ссылки открываются из текста.
function consentText(lang: Lang): string {
  return [
    t(lang, "consent.title"),
    "",
    `<blockquote>${
      t(lang, "consent.text", {
        license: LICENSE_URL,
        privacy: PRIVACY_URL,
      })
    }</blockquote>`,
    "",
    t(lang, "consent.note"),
  ].join("\n");
}

function consentKeyboard(lang: Lang): InlineKeyboard {
  return [
    [{ text: t(lang, "consent.accept"), callback_data: "consent_accept" }],
    [{ text: t(lang, "consent.decline"), callback_data: "consent_decline" }],
    [{
      text: t(lang, "consent.change_lang"),
      callback_data: "lang_menu|consent",
    }],
  ];
}

// showConsent — экран согласия. Первый показ заменяет экран выбора языка,
// повторный — переписывает текущий пузырь.
async function showConsent(
  chatId: number,
  msgId: number,
  lang: Lang,
): Promise<void> {
  if (msgId > 0) {
    await renderScreen(chatId, msgId, consentText(lang), consentKeyboard(lang));
    return;
  }
  await replaceScreen(chatId, consentText(lang), consentKeyboard(lang));
}

// showDocs — документы: лицензия и политика. Единственное место со ссылками.
async function showDocs(
  chatId: number,
  msgId: number,
  lang: Lang,
): Promise<void> {
  await renderScreen(
    chatId,
    msgId,
    `${t(lang, "docs.title")}\n\n<blockquote>${t(lang, "docs.intro")}</blockquote>`,
    [
      [{ text: t(lang, "docs.license"), url: LICENSE_URL }],
      [{ text: t(lang, "docs.privacy"), url: PRIVACY_URL }],
      [{ text: t(lang, "common.back"), callback_data: "back:main" }],
    ],
  );
}

function mainMenuText(
  ctx: BotContext,
  tgId: number,
  name: string,
  lang: Lang,
  extra = "",
): string {
  const greeting = name
    ? t(lang, "menu.greeting", { comma: ",", name: escapeHtml(name) })
    : t(lang, "menu.greeting.plain");

  let status = t(lang, "menu.status.none");
  const state = subscriptionState(ctx);
  if (state?.active) {
    status = t(lang, "menu.status.active", {
      rem: t(lang, "common.days", { days: state.days }),
    });
  } else if (ctx.account) {
    status = t(lang, "menu.status.expired");
  }

  const parts = [
    greeting,
    "",
    t(lang, "menu.body", { id: tgId, status }),
  ];
  if (extra) parts.push("", extra);
  parts.push("", t(lang, "menu.choose"));
  return parts.join("\n");
}

async function mainMenuButtons(
  tgId: number,
  isAdmin: boolean,
  linked: boolean,
  lang: Lang,
): Promise<InlineKeyboard> {
  const buttons: InlineKeyboard = [
    [{ text: t(lang, "menu.connect"), callback_data: "connect:main" }],
    [
      { text: t(lang, "menu.profile"), callback_data: "profile" },
      { text: t(lang, "menu.support"), callback_data: "support" },
    ],
    [
      { text: t(lang, "sub.title_short"), callback_data: "sub:main" },
      { text: t(lang, "menu.referral"), callback_data: "referral:main" },
    ],
  ];
  if (!linked) {
    // Вход через Google даёт доступ к платным узлам и к общей подписке с
    // приложением — поэтому кнопка стоит сразу в главном меню.
    const loginUrl = await googleLoginUrl(tgId);
    buttons.push([
      loginUrl
        ? { text: t(lang, "profile.login"), url: loginUrl }
        : { text: t(lang, "profile.login"), callback_data: "google_login" },
    ]);
  }
  // Документы — отдельным экраном: лицензия и политика только там.
  buttons.push([
    { text: t(lang, "menu.docs"), callback_data: "docs" },
  ]);
  // Поблагодарить разработчика — на виду, а не спрятано в поддержке.
  buttons.push([
    { text: t(lang, "thanks.button"), callback_data: "thanks" },
  ]);
  // Приложение — сразу под благодарностью: его ищут чаще, чем настройки.
  buttons.push([
    { text: t(lang, "sub.app"), url: APP_URL },
  ]);
  // Язык — в настройках; в меню он лишний.
  buttons.push([
    {
      text: t(lang, "settings.title").replace(/<[^>]+>/gu, ""),
      callback_data: "settings",
    },
  ]);
  if (isAdmin) {
    buttons.push([{ text: t(lang, "menu.admin"), callback_data: "admin" }]);
  }
  return buttons;
}

function adminMenuButtons(): InlineKeyboard {
  return [
    [
      { text: "📊 Статистика", callback_data: "admin_stats" },
      { text: "📢 Рассылка", callback_data: "admin_broadcast" },
    ],
    [
      { text: "🎁 Выдать подписку", callback_data: "admin_give_sub" },
    ],
    [
      { text: "🗑 Забрать подписку", callback_data: "admin_take_sub" },
      { text: "📩 Тикеты", callback_data: "admin_tickets" },
    ],
    [
      { text: "🚫 Баны", callback_data: "admin_bans" },
      { text: "◀️ Назад", callback_data: "back" },
    ],
  ];
}

function adminBackButtons(): InlineKeyboard {
  return [[{ text: "◀️ Назад", callback_data: "admin" }]];
}

function backButton(lang: Lang | null): InlineKeyboard {
  return [[{ text: t(lang, "common.back"), callback_data: "back" }]];
}

// Откуда открыт экран: из главного меню или из профиля. «Назад» должна
// возвращать туда, откуда пришли, а не всегда в главное меню.
type ScreenOrigin = "main" | "profile";

function originOf(data: string): ScreenOrigin {
  return data.endsWith(":profile") ? "profile" : "main";
}

function backTo(origin: ScreenOrigin, lang: Lang): InlineKeyboard {
  return [[{ text: t(lang, "common.back"), callback_data: `back:${origin}` }]];
}

function profileText(
  ctx: BotContext,
  tgId: number,
  name: string,
  lang: Lang,
): string {
  const title = name
    ? t(lang, "profile.title", { comma: ",", name: escapeHtml(name) })
    : t(lang, "profile.title.plain");

  const account = ctx.account;
  let statusLine = t(lang, "profile.status.none");
  let accountLine = t(lang, "profile.account.unlinked");

  if (account) {
    accountLine = t(lang, "profile.account.linked", {
      email: escapeHtml(account.email ?? "—"),
    });
    const state = subscriptionState(ctx);
    if (state?.active) {
      statusLine = t(lang, "profile.status.active", {
        date: new Date(state.expiry).toISOString().slice(0, 16).replace("T", " "),
        days: state.days,
      });
    } else if (ctx.subscription) {
      statusLine = t(lang, "profile.status.expired");
    }
  }

  const lines = [
    title,
    "",
    t(lang, "profile.lead"),
    "",
    statusLine,
    "",
    accountLine,
    t(lang, "profile.id", { id: tgId }),
  ];

  const since = formatDate(ctx.user?.created_at);
  if (since) lines.push(t(lang, "profile.since", { date: since }));
  lines.push(t(lang, "profile.referrals", {
    count: ctx.referrals ?? 0,
    days: ctx.referral_days ?? 0,
  }));

  if (ctx.payments?.length) {
    lines.push("", t(lang, "profile.payments.title"));
    for (const payment of ctx.payments.slice(0, 3)) {
      lines.push(t(lang, "profile.payments.item", {
        date: formatDate(payment.created_at),
        amount: formatAmount(payment.withdraw_amount ?? payment.amount),
      }));
    }
  }

  return lines.join("\n");
}

// referralText — экран «Пригласить друзей».
//
// Данные берутся из контекста (bot_context): сколько пришло, сколько
// оплатили, сколько дней заработано и кто именно пришёл.
function referralText(
  ctx: BotContext,
  tgId: number,
  lang: Lang,
): { html: string; link: string } {
  const username = BOT_USERNAME || "NAUA_Security_Mirage_Bot";
  const link = `https://t.me/${username}?start=ref_${tgId}`;

  const count = ctx.referrals ?? 0;
  const days = ctx.referral_days ?? 0;
  const paid = ctx.paid_referrals ?? 0;

  const lines = [
    t(lang, "referral.title"),
    "",
    t(lang, "referral.text", { link }),
    "",
    t(lang, "referral.stats", { count, days }),
  ];

  const left = friendsToMilestone(paid);
  if (left < 5) {
    lines.push(t(lang, "referral.progress", { left }));
  }

  const list = ctx.referrals_list ?? [];
  lines.push("", t(lang, "referral.list.title"));
  if (!list.length) {
    lines.push(t(lang, "referral.list.empty"));
  } else {
    for (const item of list.slice(0, 10)) {
      const status = item.plan && item.plan !== "trial"
        ? t(lang, "referral.list.paid")
        : t(lang, "referral.list.waiting");
      const name = item.name || (item.username ? `@${item.username}` : `#${item.id}`);
      lines.push(t(lang, "referral.list.item", {
        name: escapeHtml(name),
        status,
      }));
    }
  }

  return { html: lines.join("\n"), link };
}

// ===================== Google-вход =====================

async function googleLoginUrl(
  tgId: number,
  screenMessageId = 0,
): Promise<string | null> {
  try {
    // screen_msg — id сообщения, который функция перепишет после входа.
    // Благодаря этому в чате остаётся одно сообщение вместо трёх.
    const screen = screenMessageId > 0 ? `&screen_msg=${screenMessageId}` : "";
    const response = await fetch(
      `${SUPABASE_URL}/functions/v1/telegram-auth?action=start&tg_id=${tgId}${screen}`,
      {
        headers: {
          apikey: Deno.env.get("SUPABASE_ANON_KEY") || "",
          Authorization: `Bearer ${Deno.env.get("SUPABASE_ANON_KEY") || ""}`,
          "x-bot-secret": Deno.env.get("BOT_INTERNAL_SECRET") || "",
        },
      },
    );
    if (!response.ok) return null;
    const parsed = await response.json();
    return typeof parsed?.url === "string" ? parsed.url : null;
  } catch {
    return null;
  }
}

// ===================== Сообщения пользователю =====================

// showScreen рисует экран в единственном сообщении чата.
//
// Если экран ещё не создан — отправляем новое сообщение и запоминаем его id.
// Если создан — переписываем его: так в чате всегда одно сообщение бота,
// а меню не дублируется в разных местах.
// replaceScreen — удаляет прежний экран и присылает новый.
//
// Так работает /start: в чате не копится старые меню и приветствия, а
// актуальный экран всегда свежий и снизу.
async function replaceScreen(
  chatId: number,
  text: string,
  keyboard?: InlineKeyboard,
): Promise<number> {
  const old = await getScreenId(chatId);
  if (old > 0) {
    await telegram.deleteMessage(chatId, old);
  }
  const created = await telegram.sendMessage(chatId, text, keyboard);
  if (created) await setScreenId(chatId, created);
  return created ?? 0;
}

// renderScreen — перерисовывает экран чата.
//
// Если сообщение экрана недоступно (удалено, принадлежит другому чату или
// правка запрещена), создаём новый пузырь и запоминаем его. Иначе
// пользователь жмёт кнопку и не видит ничего — «молчащий» бот.
async function renderScreen(
  chatId: number,
  msgId: number,
  text: string,
  keyboard?: InlineKeyboard,
): Promise<void> {
  if (msgId > 0) {
    const result = await telegram.editMessage(chatId, msgId, text, keyboard);
    // "unchanged" — экран уже в нужном виде, дубликат не нужен.
    if (result !== "error") return;
  }
  const created = await telegram.sendMessage(chatId, text, keyboard);
  if (!created) return;
  await setScreenId(chatId, created);
  // Старый пузырь, если он ещё жив, убираем: экран в чате должен быть один.
  if (msgId > 0) await telegram.deleteMessage(chatId, msgId);
}

// showScreen — рисует экран.
//
// fresh = true  -> удалить старый и прислать новый (/start);
// fresh = false -> переписать текущий пузырь (переходы внутри меню).
async function showScreen(
  chatId: number,
  text: string,
  keyboard?: InlineKeyboard,
  knownId = 0,
  fresh = false,
): Promise<void> {
  if (fresh) {
    await replaceScreen(chatId, text, keyboard);
    return;
  }
  const screen = knownId > 0 ? knownId : await getScreenId(chatId);
  if (screen > 0) {
    const result = await telegram.editMessage(chatId, screen, text, keyboard);
    // "unchanged" — экран уже в нужном виде, дубликат не нужен.
    // "error" — правка не удалась (сообщение удалено/нет прав): тогда
    // создаём экран заново, иначе пользователь останется без меню.
    if (result !== "error") return;
  }
  const created = await telegram.sendMessage(chatId, text, keyboard);
  if (created) await setScreenId(chatId, created);
}

async function showMainMenu(
  ctx: BotContext,
  chatId: number,
  name: string,
  isAdmin: boolean,
  lang: Lang,
  fresh = false,
): Promise<void> {
  await showScreen(
    chatId,
    mainMenuText(ctx, chatId, name, lang),
    await mainMenuButtons(chatId, isAdmin, Boolean(ctx.account), lang),
    0,
    fresh,
  );
}

// profileKeyboard — кнопки профиля: подключение, вход/выход из Google,
// язык, рефералка.
async function profileKeyboard(
  ctx: BotContext,
  chatId: number,
  lang: Lang,
): Promise<InlineKeyboard> {
  const account = ctx.account;
  const state = subscriptionState(ctx);

  const keyboard: InlineKeyboard = [
    [{ text: t(lang, "menu.connect"), callback_data: "connect:profile" }],
  ];
  if (state?.active) {
    // Подписка жива — главное действие здесь её продлить.
    keyboard.push([{ text: t(lang, "sub.renew"), callback_data: "sub:profile" }]);
  } else {
    keyboard.push([{ text: t(lang, "sub.title_short"), callback_data: "sub:profile" }]);
  }
  keyboard.push([
    { text: t(lang, "menu.referral"), callback_data: "referral:profile" },
  ]);
  keyboard.push([
    { text: t(lang, "profile.payments.button"), callback_data: "profile_payments" },
  ]);
  keyboard.push([
    { text: t(lang, "sub.app"), url: APP_URL },
  ]);
  if (!account) {
    const loginUrl = await googleLoginUrl(chatId);
    keyboard.push([
      loginUrl
        ? { text: t(lang, "profile.login"), url: loginUrl }
        : { text: t(lang, "profile.login"), callback_data: "google_login" },
    ]);
  } else {
    keyboard.push([
      { text: t(lang, "profile.logout"), callback_data: "google_logout" },
    ]);
  }
  keyboard.push([
    { text: t(lang, "profile.refresh"), callback_data: "profile_refresh" },
    { text: t(lang, "common.back"), callback_data: "back" },
  ]);
  return keyboard;
}

async function showProfile(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  name: string,
  lang: Lang,
): Promise<void> {
  await renderScreen(
    chatId,
    msgId,
    profileText(ctx, chatId, name, lang),
    await profileKeyboard(ctx, chatId, lang),
  );
}

// showSettings — настройки: язык, приложение, документы, выход.
//
// Язык доступен и здесь, и на экране входа: нажал не туда — вернулся и
// выбрал. Выход из Google-аккаунта есть и тут, и в профиле.
async function showSettings(
  chatId: number,
  msgId: number,
  lang: Lang,
  ctx: BotContext,
): Promise<void> {
  const keyboard: InlineKeyboard = [];
  if (ctx.account) {
    // Выход из Google-аккаунта — первым: он ищется чаще всего.
    keyboard.push([
      { text: t(lang, "profile.logout"), callback_data: "google_logout" },
    ]);
  }
  keyboard.push([
    { text: t(lang, "settings.language"), callback_data: "lang_menu|settings" },
  ]);
  keyboard.push([{ text: t(lang, "common.back"), callback_data: "back" }]);

  await renderScreen(
    chatId,
    msgId,
    `${t(lang, "settings.title")}\n\n${t(lang, "settings.lead")}`,
    keyboard,
  );
}

// showPayments — история платежей пользователя.
async function showPayments(
  chatId: number,
  msgId: number,
  lang: Lang,
  ctx: BotContext,
): Promise<void> {
  const payments = ctx.payments ?? [];
  const lines = [t(lang, "profile.payments.screen"), ""];
  if (!payments.length) {
    lines.push(t(lang, "profile.payments.empty"));
  } else {
    for (const payment of payments) {
      lines.push(t(lang, "profile.payments.item", {
        date: formatDate(payment.created_at),
        amount: formatAmount(payment.withdraw_amount ?? payment.amount),
      }));
    }
  }
  await renderScreen(chatId, msgId, lines.join("\n"), backTo("profile", lang));
}

// attachmentOf — вложение сообщения: скрин, видео или файл.
//
// Логи приходят файлом .txt, скрин — фотографией, запись экрана — видео.
function attachmentOf(message: TelegramMessage): FileRef | null {
  if (message.photo?.length) {
    const photo = message.photo[message.photo.length - 1];
    return { file_id: photo.file_id, kind: "photo" };
  }
  if (message.video) {
    return { file_id: message.video.file_id, kind: "video" };
  }
  if (message.document) {
    return {
      file_id: message.document.file_id,
      kind: "document",
      name: message.document.file_name,
      mime: message.document.mime_type,
    };
  }
  return null;
}

// attachmentName — как назвать вложение в тексте.
function attachmentName(lang: Lang, kind?: string | null): string {
  if (kind === "photo") return t(lang, "support.attachment.photo");
  if (kind === "video") return t(lang, "support.attachment.video");
  return t(lang, "support.attachment.document");
}

// showSignInError — экран «вход через Google не удался».
//
// Раньше при неудаче бот молча показывал приветствие заново: человек не
// понимал, что произошло, и думал, что кнопка сломалась. Теперь причина
// объяснена и рядом есть кнопка «Войти в Google-аккаунт».
async function showSignInError(
  chatId: number,
  lang: Lang,
  reason: string,
): Promise<void> {
  const text = reason === "denied"
    ? t(lang, "google.error.denied")
    : reason === "expired"
    ? t(lang, "google.error.expired")
    : reason === "state"
    ? t(lang, "google.error.state")
    : reason === "exchange"
    ? t(lang, "google.error.exchange")
    : reason === "no_code"
    ? t(lang, "google.error.no_code")
    : reason === "internal"
    ? t(lang, "google.error.internal")
    : t(lang, "google.error.default");

  await renderScreen(
    chatId,
    await getScreenId(chatId),
    `${t(lang, "google.error.title")}\n\n<blockquote>${text}</blockquote>`,
    [
      [{ text: t(lang, "profile.login"), callback_data: "google_login" }],
      [{ text: t(lang, "profile.language.change"), callback_data: "lang_menu|welcome" }],
    ],
  );
}

// showThanks — «Поблагодарить разработчика»: три способа, как в приложении.
async function showThanks(
  chatId: number,
  msgId: number,
  lang: Lang,
): Promise<void> {
  await renderScreen(
    chatId,
    msgId,
    [
      t(lang, "thanks.title"),
      t(lang, "thanks.subtitle"),
      "",
      t(lang, "thanks.hint"),
      "",
      t(lang, "thanks.cloudtips"),
      "",
      t(lang, "thanks.tributeTg"),
      "",
      t(lang, "thanks.tributeWeb"),
    ].join("\n"),
    [
      [{ text: "💳 CloudTips", url: CLOUDTIPS_URL }],
      [{ text: "🎁 Tribute в Telegram", url: TRIBUTE_TG_URL }],
      [{ text: "🌐 Tribute в браузере", url: TRIBUTE_WEB_URL }],
      [{ text: t(lang, "common.back"), callback_data: "back" }],
    ],
  );
}

// showSupport — экран поддержки.
//
// Поддержка одна: заявка в этом чате. Ссылка в отдельный чат убрана —
// было двусмысленно, куда писать. Свои заявки пользователь видит тут же.
async function showSupport(
  chatId: number,
  msgId: number,
  lang: Lang,
): Promise<void> {
  const latest = await getLatestTicket(chatId);
  const created = latest ? Date.parse(latest.created_at) : 0;
  const cooling = Number.isFinite(created) && Date.now() - created < 60_000;

  const lines = [t(lang, "support.title"), "", t(lang, "support.text")];
  if (cooling) {
    // Кулдаун показываем внутри экрана: отдельное сообщение путало — было
    // непонятно, что произошло, и экран поддержки не открывался вовсе.
    lines.push("", t(lang, "support.cooldown"));
  } else {
    // Сразу включаем режим ввода обращения: следующее сообщение юзера станет
    // текстом тикета.
    await setState(chatId, "ticket_draft");
  }

  const tickets = await listUserTickets(chatId, 5);
  const open = tickets.filter((ticket) => ticket.status === "open").length;
  lines.push("", t(lang, "support.stats", { total: tickets.length, open }));

  const keyboard: InlineKeyboard = [];
  if (tickets.length) {
    lines.push("", t(lang, "support.list.title"));
    for (const ticket of tickets) {
      const status = ticket.status === "open"
        ? t(lang, "support.status.open")
        : t(lang, "support.status.done");
      lines.push(t(lang, "support.list.item", {
        id: ticket.id,
        date: formatDate(ticket.created_at),
        status,
      }));
      keyboard.push([
        {
          text: `📩 #${ticket.id} · ${status}`,
          callback_data: `ticket:${ticket.id}`,
        },
      ]);
    }
  }
  keyboard.push([{ text: t(lang, "common.back"), callback_data: "back" }]);

  await renderScreen(chatId, msgId, lines.join("\n"), keyboard);
}

// showTicketThread — переписка по заявке.
async function showTicketThread(
  chatId: number,
  msgId: number,
  lang: Lang,
  ticketId: number,
): Promise<void> {
  const ticket = await getTicket(ticketId);
  if (!ticket || ticket.user_id !== chatId) {
    await renderScreen(chatId, msgId, t(lang, "common.hint"), backButton(lang));
    return;
  }
  const messages = await listTicketMessages(ticketId);
  const lines = [
    t(lang, "support.thread", { id: ticket.id }),
    "",
    t(lang, "support.list.item", {
      id: ticket.id,
      date: formatDate(ticket.created_at),
      status: ticket.status === "open"
        ? t(lang, "support.status.open")
        : t(lang, "support.status.done"),
    }),
    "",
  ];
  for (const message of messages) {
    const who = message.from_user ? t(lang, "support.you") : t(lang, "support.admin");
    const body = message.text
      ? `${who} ${message.text}`
      : `${who} ${t(lang, "support.attachment", {
        what: attachmentName(lang, message.file_kind),
      })}`;
    lines.push(body, "");
  }
  await renderScreen(
    chatId,
    msgId,
    lines.join("\n").trim(),
    [
      [{ text: t(lang, "support.open"), callback_data: `ticket_reply:${ticket.id}` }],
      [{ text: t(lang, "common.back"), callback_data: "support" }],
    ],
  );
}

async function showReferral(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  lang: Lang,
  origin: ScreenOrigin = "main",
): Promise<void> {
  const { html, link } = referralText(ctx, chatId, lang);
  const share = `https://t.me/share/url?url=${
    encodeURIComponent(link)
  }&text=${encodeURIComponent(t(lang, "referral.share"))}`;
  await renderScreen(chatId, msgId, html, [
    [{ text: t(lang, "referral.invite"), url: share }],
    // copy_text — Telegram кладёт ссылку в буфер обмена по нажатию.
    [{ text: t(lang, "referral.copy"), copy_text: { text: link } }],
    [{ text: t(lang, "common.back"), callback_data: `back:${origin}` }],
  ]);
}

async function showConnect(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  lang: Lang,
  origin: ScreenOrigin = "main",
): Promise<void> {
  const account = ctx.account;
  if (!account) {
    await renderScreen(
      chatId,
      msgId,
      `${t(lang, "connect.title")}\n\n${t(lang, "connect.need_login")}`,
      [
        [{ text: t(lang, "profile.login"), callback_data: "google_login" }],
        [{ text: t(lang, "common.back"), callback_data: `back:${origin}` }],
      ],
    );
    return;
  }
  const link = await subscriptionLink(chatId);
  await renderScreen(
    chatId,
    msgId,
    `${t(lang, "connect.title")}\n\n${t(lang, "connect.text")}\n\n${t(lang, "connect.hint")}`,
    [
      [{ text: t(lang, "connect.open"), url: await connectPage(link, lang, chatId) }],
      [{ text: t(lang, "connect.copy"), copy_text: { text: link } }],
      [{ text: t(lang, "common.back"), callback_data: `back:${origin}` }],
    ],
  );
}

// ===================== Подписка и оплата =====================

// subscriptionState — состояние подписки пользователя (по его Google-аккаунту).
type SubscriptionState = {
  active: boolean;
  expiry: number;
  days: number;
};

// Считается из уже загруженного контекста: отдельный запрос в базу не нужен.
function subscriptionState(ctx: BotContext): SubscriptionState | null {
  if (!ctx.account) return null;
  const subscription = ctx.subscription;
  if (!subscription?.is_active || !subscription.paid_until) {
    return { active: false, expiry: 0, days: 0 };
  }
  const expiry = Date.parse(subscription.paid_until);
  if (!Number.isFinite(expiry)) return { active: false, expiry: 0, days: 0 };
  return {
    active: expiry > Date.now(),
    expiry,
    days: Math.max(0, Math.ceil((expiry - Date.now()) / 86_400_000)),
  };
}

// subscriptionStatusLine — строка со статусом подписки.
function subscriptionStatusLine(
  state: SubscriptionState | null,
  lang: Lang,
): string {
  if (!state) return t(lang, "sub.need_login");
  if (!state.active) return t(lang, "sub.status.expired");
  return t(lang, "sub.status.active", {
    date: new Date(state.expiry).toISOString().slice(0, 16).replace("T", " "),
    days: state.days,
  });
}

// showSubscription — экран подписки: что входит, статус, тарифы, поддержка.
let plansCache: { at: number; plans: Plan[] } | null = null;

// activePlans — тарифы из app_config с коротким кэшем.
//
// Один и тот же список читают приложение, бот и вебхук. Кэш на минуту
// избавляет от запроса к базе на каждое нажатие, но не мешает цене
// обновиться почти сразу после правки в Supabase.
async function activePlans(): Promise<Plan[]> {
  const now = Date.now();
  if (plansCache && now - plansCache.at < 60_000) return plansCache.plans;
  try {
    const { data, error } = await supabase()
      .from("app_config")
      .select("payload")
      .eq("id", "subscription_plans")
      .maybeSingle();
    if (error) {
      console.warn("app_config plans unavailable, using built-in plans", { error: error.message });
      return plansCache?.plans ?? PLANS;
    }
    const plans = parsePlansPayload(
      (data as { payload?: unknown } | null)?.payload,
    );
    plansCache = { at: now, plans };
    return plans;
  } catch (error) {
    console.warn("app_config plans lookup failed, using built-in plans", {
      error: error instanceof Error ? error.message : error,
    });
    return plansCache?.plans ?? PLANS;
  }
}

async function showSubscription(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  lang: Lang,
  origin: ScreenOrigin = "main",
): Promise<void> {
  const account = ctx.account;
  const state = subscriptionState(ctx);

  const lines = [
    t(lang, "sub.title"),
    "",
    `<blockquote>${t(lang, "sub.intro")}</blockquote>`,
    "",
    subscriptionStatusLine(state, lang),
    "",
    `<blockquote>${t(lang, "sub.faq")}</blockquote>`,
  ];

  const keyboard: InlineKeyboard = [];
  if (!account) {
    // Без Google-аккаунта платить нечем: к аккаунту привязывается подписка.
    keyboard.push([
      { text: t(lang, "profile.login"), callback_data: "google_login" },
    ]);
  } else {
    // Подсказка — часть текста, а не кнопка: Telegram не принимает кнопки
    // без callback_data/url и отвергает всю клавиатуру целиком.
    const plans = await activePlans();
    const best = bestSaving(plans);
    lines.push("", t(lang, "sub.choose"));
    if (best > 0) {
      lines.push(t(lang, "sub.choose.hint", { saving: best }));
    }
    lines.push("", t(lang, "sub.pay.hint"));
    for (const plan of plans) {
      const period = lang === "en" ? plan.labelEn : plan.labelRu;
      const saving = savingPercent(plan, plans);
      // Выгода показывается у каждого тарифа, лучший помечается огоньком.
      const badge = saving <= 0
        ? ""
        : saving === best
        ? " " + t(lang, "sub.badge", { saving })
        : " (" + t(lang, "sub.saving", { saving }) + ")";
      const price = state?.active
        ? t(lang, "sub.renewFor", { amount: plan.amountRub, period })
        : t(lang, "sub.pay", { amount: plan.amountRub, period });
      keyboard.push([
        {
          text: price + badge,
          callback_data: `plan:${plan.amountRub}:${origin}`,
        },
      ]);
    }
    keyboard.push([
      { text: t(lang, "sub.check"), callback_data: "sub_check" },
    ]);
  }
  keyboard.push([
    { text: t(lang, "sub.support"), url: SUPPORT_URL },
  ]);
  keyboard.push([{ text: t(lang, "common.back"), callback_data: `back:${origin}` }]);

  await renderScreen(chatId, msgId, lines.join("\n"), keyboard);
}

// showPayment — экран оплаты выбранного тарифа со ссылкой.
async function showPayment(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  lang: Lang,
  amountRub: number,
  origin: ScreenOrigin = "main",
): Promise<void> {
  const account = ctx.account;
  if (!account) {
    await renderScreen(
      chatId,
      msgId,
      t(lang, "sub.need_login"),
      [[{ text: t(lang, "profile.login"), callback_data: "google_login" }]],
    );
    return;
  }
  const plan = (await activePlans()).find((item) => item.amountRub === amountRub);
  if (!plan) {
    // The price changed while this message was on screen. Re-render the
    // subscription screen with the current tariffs instead of claiming the
    // user has not paid.
    await showSubscription(ctx, chatId, msgId, lang, origin);
    return;
  }

  if (!yoomoneyWallet()) {
    // Секрет не задан: честно говорим, что оплата недоступна, вместо битой
    // ссылки на оплату.
    await renderScreen(
      chatId,
      msgId,
      t(lang, "sub.pay.unavailable"),
      [[{ text: t(lang, "sub.support"), url: SUPPORT_URL }]],
    );
    return;
  }

  const period = lang === "en" ? plan.labelEn : plan.labelRu;
  const url = buildPaymentUrl(
    plan,
    account.user_id,
    lang === "en" ? "NAUA VPN subscription" : "NAUA VPN Подписка",
  );

  await renderScreen(
    chatId,
    msgId,
    [
      t(lang, "sub.pay_title"),
      "",
      t(lang, "sub.pay_body"),
      "",
      `💳 <b>${period}</b> — ${plan.amountRub} рублей (${
        t(lang, "common.days", { days: plan.days })
      })`,
    ].join("\n"),
    [
      [{ text: t(lang, "sub.pay", { amount: plan.amountRub, period }), url }],
      [{ text: t(lang, "common.back"), callback_data: `sub:${origin}` }],
    ],
  );
}

// checkSubscription — пользователь нажал «Я оплатил».
async function checkSubscription(
  ctx: BotContext,
  chatId: number,
  msgId: number,
  lang: Lang,
  origin: ScreenOrigin = "main",
): Promise<void> {
  const state = subscriptionState(ctx);
  if (!state) {
    await renderScreen(
      chatId,
      msgId,
      t(lang, "sub.need_login"),
      [[{ text: t(lang, "profile.login"), callback_data: "google_login" }]],
    );
    return;
  }
  if (state.active) {
    const link = await subscriptionLink(chatId);
    await renderScreen(
      chatId,
      msgId,
      t(lang, "sub.paid", {
        date: new Date(state.expiry).toISOString().slice(0, 16).replace("T", " "),
      }),
      [
        [{ text: t(lang, "connect.open"), url: await connectPage(link, lang, chatId) }],
        [{ text: t(lang, "connect.copy"), copy_text: { text: link } }],
        [{ text: t(lang, "common.back"), callback_data: `back:${origin}` }],
      ],
    );
    return;
  }
  await renderScreen(
    chatId,
    msgId,
    t(lang, "sub.not_paid"),
    [
      [{ text: t(lang, "sub.check"), callback_data: `sub_check:${origin}` }],
      [{ text: t(lang, "common.back"), callback_data: `sub:${origin}` }],
    ],
  );
}

// ===================== Рефералы =====================

async function handleReferral(
  ctx: BotContext,
  chatId: number,
  refId: number,
  name: string,
  username: string,
  lang: Lang,
): Promise<void> {
  if (!refId || refId === chatId) {
    await showMainMenu(ctx, chatId, name, chatId === ADMIN_ID, lang);
    return;
  }
  if (ctx.user?.referred_by) {
    await showMainMenu(ctx, chatId, name, chatId === ADMIN_ID, lang);
    return;
  }
  await upsertUser(chatId, username, name);
  await updateUser(chatId, { referred_by: refId });
  await telegram.sendMessage(
    refId,
    t(lang, "referral.joined", { name: name ? escapeHtml(name) : "" }),
  );
  await telegram.sendMessage(chatId, t(lang, "referral.joined.you"));
  await showMainMenu(ctx, chatId, name, chatId === ADMIN_ID, lang);
}

// ===================== Поддержка =====================

// adminTicketButtons — кнопки для админа: ответить и посмотреть аккаунт в
// панели. «Написать в личку» убрано: в уведомлении и так есть @username.
function adminTicketButtons(
  ticketId: number,
  chatId: number,
  userId?: string | null,
): InlineKeyboard {
  const rows: InlineKeyboard = [
    [
      {
        text: "💬 Ответить",
        callback_data: `reply_ticket:${ticketId}:${chatId}`,
      },
    ],
  ];
  if (userId) {
    rows.push([
      {
        text: "🔑 Аккаунт в панели",
        url: `https://app.supabase.com/project/orwwaptbxmwxvfwfqavc/editor/public.subscriptions?id=eq.${userId}`,
      },
    ]);
  }
  return rows;
}

// notifyAdminAboutReply — админу приходит ответ пользователя по заявке.
async function notifyAdminAboutReply(
  ticketId: number,
  body: string,
  attachment?: FileRef | null,
): Promise<void> {
  const ticket = await getTicket(ticketId);
  const chatId = ticket?.user_id ?? ADMIN_ID;
  const account = await getAccount(chatId);
  const user = await getUser(chatId);
  const who = user?.username
    ? `@${escapeHtml(user.username)} (<code>${chatId}</code>)`
    : `<code>${chatId}</code>`;
  const head = `💬 <b>Ответ по заявке #${ticketId}</b> от ${who}` +
    (account?.email ? `\n🔑 ${escapeHtml(account.email)}` : "");
  const text = body ? `${head}\n\n${safeText(body)}` : head;
  await telegram.sendMessage(
    ADMIN_ID,
    text,
    adminTicketButtons(ticketId, chatId, account?.user_id),
  );
  if (attachment) {
    await sendAttachmentToAdmin(attachment, `Ответ по заявке #${ticketId}`);
  }
}

// sendAttachmentToAdmin — админ получает файл (скрин, видео, логи).
//
// Файл лежит в Telegram и по file_id его не переслать — сначала копируем
// в наш чат, потом отправляем.
async function sendAttachmentToAdmin(
  attachment: FileRef,
  caption: string,
): Promise<boolean> {
  const copied = await telegram.copyFile(attachment.file_id);
  if (!copied) return false;
  return await telegram.sendFile(ADMIN_ID, copied, attachment.kind, caption);
}

async function saveTicket(
  chatId: number,
  text: string,
  name: string,
  username: string,
  lang: Lang,
  screenId = 0,
  fileId?: string | null,
  fileKind?: string | null,
): Promise<void> {
  const ticketId = await createTicket(chatId, text, fileId ?? null, fileKind ?? null);
  const label = username
    ? `@${escapeHtml(username)} (<code>${chatId}</code>)`
    : `<code>${chatId}</code>`;
  // «Отправлено» — на том же экране и с кнопкой выхода в меню.
  await renderScreen(
    chatId,
    screenId || (await getScreenId(chatId)),
    t(lang, "support.sent"),
    [[{ text: t(lang, "textmenu.home"), callback_data: "back" }]],
  );

  // Админу — не только id, но и Google-аккаунт: так его видно сразу.
  const account = await getAccount(chatId);
  const head = account
    ? `📩 <b>Новый тикет</b> от ${label}\n🔑 Google-аккаунт: ${escapeHtml(account.email ?? "—")}`
    : `📩 <b>Новый тикет</b> от ${label}`;
  const body = text ? `${head}\n\n${safeText(text)}` : head;
  await telegram.sendMessage(
    ADMIN_ID,
    body,
    adminTicketButtons(ticketId ?? 0, chatId, account?.user_id),
  );
  if (fileId && fileKind) {
    const ok = await sendAttachmentToAdmin(
      { file_id: fileId, kind: fileKind as FileRef["kind"] },
      `Файл из заявки #${ticketId ?? "—"}`,
    );
    if (!ok) {
      // Копирование не вышло — даём админу кнопку на ручную попытку.
      await telegram.sendMessage(
        ADMIN_ID,
        "📎 Файл из заявки не удалось получить автоматически.",
        [[
          {
            text: "📎 Показать файл",
            callback_data: `ticket_file:${fileKind}:${fileId}`,
          },
        ]],
      );
    }
  }
  void name;
}

// ===================== Админка =====================

async function adminStatsText(): Promise<string> {
  const { count: users } = await supabase()
    .from("bot_users")
    .select("id", { count: "exact", head: true });
  const banned = (await listBannedUsers()).length;
  const { count: linked } = await supabase()
    .from("telegram_accounts")
    .select("tg_id", { count: "exact", head: true });
  // Подписки живут в общей таблице — там же, где их видит приложение.
  const { data: subs } = await supabase()
    .from("subscriptions")
    .select("paid_until")
    .eq("is_active", true);
  let active = 0;
  for (const row of (subs ?? []) as { paid_until: string | null }[]) {
    if (!row.paid_until) continue;
    const expiry = Date.parse(row.paid_until);
    if (Number.isFinite(expiry) && expiry > Date.now()) active++;
  }
  return `📊 <b>Статистика бота</b>

Пользователей: <b>${users ?? 0}</b>
Активных подписок: <b>${active}</b>
Забанено: <b>${banned}</b>
Привязано Google-аккаунтов: <b>${linked ?? 0}</b>`;
}

async function resolveUser(input: string): Promise<number | null> {
  const clean = input.trim().replace(/^@/, "");
  if (/^\d+$/.test(clean)) return Number(clean);
  const { data } = await supabase()
    .from("bot_users")
    .select("id")
    .eq("username", clean)
    .limit(1);
  const row = (data && data[0]) as { id: number } | undefined;
  return row?.id ?? null;
}

async function banUser(targetId: number, reason: string): Promise<void> {
  await updateUser(targetId, {
    banned: true,
    ban_reason: reason,
    banned_at: nowIso(),
  });
  await telegram.sendMessage(
    targetId,
    `🚫 <b>Вы заблокированы</b> в NAUA Security Mirage.${reason ? `\n\nПричина: ${escapeHtml(reason)}` : ""}`,
  );
  await logAdmin(ADMIN_ID, "ban", targetId, reason);
}

async function unbanUser(targetId: number, reason: string): Promise<void> {
  await updateUser(targetId, {
    banned: false,
    ban_reason: "",
    banned_at: null,
  });
  await telegram.sendMessage(
    targetId,
    `✅ <b>Вы разблокированы</b> в NAUA Security Mirage.${reason ? `\n\nПричина: ${escapeHtml(reason)}` : ""}`,
  );
  await logAdmin(ADMIN_ID, "unban", targetId, reason);
}

async function warnUser(targetId: number, reason: string): Promise<void> {
  const user = await getUser(targetId);
  let warnings = (user?.warnings ?? 0) + 1;
  if (warnings > 3) warnings = 3;
  const update: Record<string, unknown> = { warnings };
  if (warnings >= 3) {
    update.banned = true;
    update.ban_reason = reason;
    update.banned_at = nowIso();
  }
  await updateUser(targetId, update);
  await telegram.sendMessage(
    targetId,
    `⚠️ <b>Предупреждение ${warnings}/3</b> в NAUA Security Mirage.${reason ? `\n\nПричина: ${escapeHtml(reason)}` : ""}`,
  );
  await logAdmin(ADMIN_ID, "warn", targetId, reason);
}

async function adminTicketsText(): Promise<string> {
  const tickets = await listTickets("open", 20);
  if (tickets.length === 0) return "📩 <b>Тикеты</b>\n\nНовых обращений нет.";
  const lines = tickets.map((ticket) => {
    const preview = safeText((ticket.message ?? "").slice(0, 60));
    return `• <code>${ticket.id}</code> от <code>${ticket.user_id}</code>\n  ${preview}`;
  });
  return `📩 <b>Открытые тикеты</b>\n\n${lines.join("\n")}`;
}

// ===================== Рассылка =====================

async function processBroadcast(): Promise<void> {
  const queue = await readBroadcastQueue();
  if (!queue) return;
  const recipients = await broadcastRecipients(
    queue.admin_id,
    queue.cursor,
    BROADCAST_CHUNK,
  );
  let sent = 0;
  let failed = 0;
  for (const chatId of recipients) {
    const ok = await telegram.sendMessage(chatId, queue.text);
    if (ok) sent++;
    else failed++;
  }
  const next = queue.cursor + BROADCAST_CHUNK;
  if (recipients.length < BROADCAST_CHUNK) {
    await finishBroadcast(queue.admin_id);
    await telegram.sendMessage(
      queue.admin_id,
      `✅ <b>Рассылка завершена</b>\n\nОтправлено: ${sent}\nОшибок: ${failed}`,
    );
    return;
  }
  await advanceBroadcast(next);
}

// ===================== Крон =====================

async function runCron(): Promise<void> {
  // Уведомления об истечении подписки.
  const { data } = await supabase()
    .from("subscriptions")
    .select("user_id,email,paid_until,tg_id")
    .not("paid_until", "is", null)
    .not("tg_id", "is", null);
  const rows = (data ?? []) as {
    user_id: string;
    email: string | null;
    paid_until: string;
    tg_id: number | null;
  }[];
  for (const row of rows) {
    if (!row.tg_id) continue;
    const expiry = Date.parse(row.paid_until);
    if (!Number.isFinite(expiry)) continue;
    const days = Math.ceil((expiry - Date.now()) / 86_400_000);
    let text = "";
    if (days === 3) {
      text = "⏳ <b>Подписка истекает через 3 дня</b>\n\nНе забудь продлить, чтобы не потерять доступ!";
    } else if (days === 1) {
      text = "⚠️ <b>Подписка истекает завтра</b>\n\nПродли подписку сейчас, чтобы оставаться на связи!";
    } else if (days === 0) {
      text = "❗ <b>Подписка истекает сегодня!</b>\n\nПродли подписку сейчас, чтобы оставаться на связи!";
    }
    if (!text) continue;
    const user = await getUser(row.tg_id);
    if (user && Date.now() - (Date.parse(user.last_notified ?? "") || 0) < 20 * 3600_000) {
      continue;
    }
    await telegram.sendMessage(row.tg_id, text, [
      [{ text: "💳 Продлить подписку", callback_data: "sub:main" }],
    ]);
    await updateUser(row.tg_id, { last_notified: nowIso() });
  }

  // Чистка старых состояний и попыток входа.
  const cutoff = new Date(Date.now() - 60 * 60_000).toISOString();
  await supabase().from("telegram_oauth_attempts").delete().lt("created_at", cutoff);
  await supabase().from("bot_state").delete().lt("updated_at", cutoff);

  // Рассылка продвигается на каждом тике.
  await processBroadcast();
}

// ===================== Обработка сообщений =====================

async function handleMessage(message: TelegramMessage): Promise<void> {
  const chatId = message.chat.id;
  const text = (message.text ?? message.caption ?? "").trim();
  if (!text) return;

  const from = message.from;
  const username = safeUsername(from?.username ?? "");
  const name = [from?.first_name, from?.last_name].filter(Boolean).join(" ");

  // Пользователь, привязка и подписка — одним запросом к базе.
  const ctx = await loadContext(chatId);
  const user = ctx.user;
  if (user?.banned) return;

  const isAdmin = chatId === ADMIN_ID;
  const lang = langOf(user);

  // Новый пользователь — сообщаем админу сразу, как только он появился.
  // Раньше уведомление стояло ниже ветки выбора языка, а новый пользователь
  // без языка из неё выходил раньше: уведомление не приходило никогда.
  if (!user) {
    await upsertUser(chatId, username, name);
    // В кэше контекста ещё лежит «пользователя нет» — сбрасываем, иначе
    // выбор языка прочитает старые данные и потеряет имя/язык.
    invalidateContext(chatId);
    await telegram.sendMessage(
      ADMIN_ID,
      `👤 <b>Новый пользователь:</b> ${
        username ? `@${escapeHtml(username)}` : `<code>${chatId}</code>`
      }`,
    );
  }

  // Самое первое, что видит человек, — выбор языка. Он задаётся один раз
  // и сохраняется в bot_users.lang.
  if (!lang) {
    await showLanguageSelection(chatId);
    return;
  }

  if (user && (user.username !== username || user.name !== name)) {
    await upsertUser(chatId, username, name);
  }

  // Согласие с документами — до входа в Google и до всех разделов.
  // Старые пользователи тоже увидят его один раз: consent_version пустой.
  if (!hasConsent(user)) {
    await showConsent(chatId, 0, lang);
    return;
  }

  // Ворота: пока Google-аккаунт не привязан, доступно только приветствие
  // (вход + смена языка). Меню и разделы закрыты — для всех, включая админа.
  const account = ctx.account;
  if (!account) {
    await showWelcome(chatId, lang, name);
    return;
  }

  // Состояния диалогов.
  const state = await getState(chatId);
  if (state) {
    if (text === "Отмена" || text === "/cancel") {
      await clearState(chatId);
      await telegram.sendMessage(chatId, t(lang, "common.cancel"));
      return;
    }
    // Команда (/stats, /sub …) всегда важнее состояния диалога: иначе
    // ответ админа в режиме «ответить на тикет» ушёл бы в текст тикета.
    if (!text.startsWith("/")) {
      if (state.state === "ticket_draft" && !isAdmin) {
        // Заявка — это текст, скрин, видео или файл с логами.
        const attachment = attachmentOf(message);
        const body = attachment ? (message.caption ?? "") : text;
        await setState(chatId, "ticket_confirm", {
          draft: body,
          file_id: attachment?.file_id ?? null,
          file_kind: attachment?.kind ?? null,
        });

        const draft: string[] = [t(lang, "support.draft.title")];
        if (body) draft.push("", `<blockquote>${escapeHtml(body)}</blockquote>`);
        if (attachment) {
          draft.push("", t(lang, "support.attachment", {
            what: attachmentName(lang, attachment.kind),
          }));
        }
        draft.push("", t(lang, "support.draft"));

        // Черновик переписывает тот же экран: в чате остаётся один пузырь бота.
        await renderScreen(
          chatId,
          await getScreenId(chatId),
          draft.join("\n"),
          [
            [
              { text: t(lang, "support.confirm"), callback_data: "confirm_ticket" },
              { text: t(lang, "support.cancel"), callback_data: "cancel" },
            ],
          ],
        );
        return;
      }

      // Ответ пользователя в уже созданную заявку.
      if (state.state?.startsWith("ticket_reply:") && !isAdmin) {
        const ticketId = Number(state.state.split(":")[1]);
        const attachment = attachmentOf(message);
        const body = attachment ? (message.caption ?? "") : text;
        await addTicketMessage(
          ticketId,
          true,
          body,
          attachment?.file_id ?? null,
          attachment?.kind ?? null,
        );
        // Новый вопрос по закрытой заявке снова открывает её: админ увидит
        // обращение в списке открытых, а не только в уведомлении.
        await setTicketStatus(ticketId, "open");
        await clearState(chatId);
        await notifyAdminAboutReply(ticketId, body, attachment);
        await renderScreen(
          chatId,
          await getScreenId(chatId),
          t(lang, "support.replied"),
          backButton(lang),
        );
        return;
      }
      if (state.state === "broadcast_text" && isAdmin) {
        const count = await countRecipients(ADMIN_ID);
        await setState(chatId, "broadcast_confirm", { text });
        await telegram.sendMessage(
          chatId,
          `📢 <b>Рассылка</b>\n\nТекст:\n<blockquote>${
            escapeHtml(text)
          }</blockquote>\n\nБудет отправлено <b>${count}</b> пользователям.\n\nПодтверди или отмени:`,
          [
            [
              { text: "✅ Подтвердить", callback_data: "confirm_broadcast" },
              { text: "❌ Отмена", callback_data: "cancel" },
            ],
          ],
        );
        return;
      }
      if (state.state === "reply" && isAdmin) {
        const target = Number(state.data?.target_id ?? 0);
        const ticketId = Number(state.data?.ticket_id ?? 0);
        await clearState(chatId);
        if (target) {
          const userLang = langOf(await getUser(target)) ?? "ru";
          // Ответ приходит с кнопками: сразу видно, как ответить или закрыть
          // вопрос, не заходя в раздел поддержки.
          const buttons: InlineKeyboard = ticketId > 0
            ? [[
              {
                text: t(userLang, "support.reply.answer"),
                callback_data: `ticket_reply:${ticketId}`,
              },
              {
                text: t(userLang, "support.reply.close"),
                callback_data: `close_ticket_user:${ticketId}`,
              },
            ]]
            : [];
          await telegram.sendMessage(
            target,
            `${t(userLang, "support.reply.head")}\n\n${escapeHtml(text)}`,
            buttons.length ? buttons : undefined,
          );
          // Ответ админа — в переписку по заявке: иначе в треде его не видно.
          if (ticketId > 0) await addTicketMessage(ticketId, false, text);
          await telegram.sendMessage(chatId, "✅ Ответ отправлен.");
        }
        return;
      }
      if (state.state === "warn" && isAdmin) {
        const target = Number(state.data?.target_id ?? 0);
        await clearState(chatId);
        if (target) await warnUser(target, text);
        return;
      }
      if (state.state === "admin_give_sub" && isAdmin) {
        await clearState(chatId);
        const [rawId, rawDays] = text.split(/\s+/);
        const target = await resolveUser(rawId ?? "");
        const days = Number(rawDays);
        if (!target || !Number.isFinite(days) || days <= 0 || days > 365) {
          await telegram.sendMessage(
            chatId,
            "❌ Формат: <code>ID или @username 30</code>",
          );
          return;
        }
        await grantSubscription(target, days);
        return;
      }
      if (state.state === "admin_take_sub" && isAdmin) {
        await clearState(chatId);
        const [rawId, rawDays] = text.split(/\s+/);
        const target = await resolveUser(rawId ?? "");
        const days = rawDays ? Number(rawDays) : 0;
        if (!target || !Number.isFinite(days) || days < 0 || days > 3650) {
          await telegram.sendMessage(
            chatId,
            "❌ Формат: <code>ID или @username [дней]</code>\nБез числа дней подписка отзывается полностью.",
          );
          return;
        }
        const account = await getAccount(target);
        const current = account ? await getSubscription(account.user_id) : null;
        const expiry = current?.paid_until ? Date.parse(current.paid_until) : 0;
        const remaining = current?.is_active && expiry > Date.now()
          ? expiry - Date.now()
          : 0;
        if (!account || !current || remaining <= 0) {
          await telegram.sendMessage(
            chatId,
            `❌ У пользователя <code>${target}</code> нет активной подписки.`,
          );
          return;
        }
        const daysMs = days * 86_400_000;
        const left = Math.ceil(remaining / 86_400_000);
        // Списание больше остатка (или без числа дней) — отзываем подписку
        // целиком: отрицательного остатка быть не должно.
        const full = days <= 0 || daysMs >= remaining;
        const plan = days <= 0
          ? "Подписка будет отозвана <b>полностью</b>."
          : full
          ? `Списываем ${days} дн. — это больше, чем осталось (${left}). <b>Подписка будет отозвана полностью.</b>`
          : `Списываем <b>${days}</b> дн. — останется <b>${
            Math.ceil((remaining - daysMs) / 86_400_000)
          }</b> дн.`;
        await setState(chatId, "admin_take_sub_confirm", { target_id: target, days });
        await telegram.sendMessage(
          chatId,
          `🗑 <b>Забрать подписку?</b>\n\n` +
            `Пользователь: <code>${target}</code>\n` +
            (account.email ? `Аккаунт: ${escapeHtml(account.email)}\n` : "") +
            `Осталось: <b>${left}</b> дн. (до ${
              new Date(expiry).toISOString().slice(0, 10)
            })\n` +
            `${plan}\n\n` +
            (full
              ? "Платные узлы отключатся."
              : "Срок подписки сократится, узлы продолжат работать."),
          [[
            { text: "✅ Забрать", callback_data: "confirm_take_sub" },
            { text: "❌ Отмена", callback_data: "cancel" },
          ]],
        );
        return;
      }
    }
  }

  // Команды.
  if (text.startsWith("/")) {
    await handleCommand(ctx, chatId, text, name, username, lang);
    return;
  }

  // Текстовое меню (совместимость с версией бота на обычной клавиатуре).
  if (await handleMenuText(ctx, chatId, text, name, lang)) return;

  if (!state) {
    await telegram.sendMessage(chatId, t(lang, "common.hint"));
  }
}

// textMenuKeys — подписи кнопок нижнего меню на обоих языках.
function textMenuKeys(key: string): string[] {
  const ru = t("ru", key);
  const en = t("en", key);
  return ru === en ? [ru] : [ru, en];
}

async function handleMenuText(
  ctx: BotContext,
  chatId: number,
  text: string,
  name: string,
  lang: Lang,
): Promise<boolean> {
  if (textMenuKeys("textmenu.profile").includes(text)) {
    await showScreen(
      chatId,
      await profileText(ctx, chatId, name, lang),
      await profileKeyboard(ctx, chatId, lang),
    );
    return true;
  }
  if (textMenuKeys("textmenu.connect").includes(text)) {
    const link = await subscriptionLink(chatId);
    await showScreen(
      chatId,
      `${t(lang, "connect.title")}\n\n${t(lang, "connect.text")}\n\n${t(lang, "connect.hint")}`,
      [
        [{ text: t(lang, "connect.open"), url: await connectPage(link, lang, chatId) }],
        [{ text: t(lang, "connect.copy"), copy_text: { text: link } }],
      ],
    );
    return true;
  }
  if (textMenuKeys("textmenu.support").includes(text)) {
    await showScreen(
      chatId,
      `${t(lang, "support.title")}\n\n${t(lang, "support.text")}`,
      backButton(lang),
    );
    await setState(chatId, "ticket_draft");
    return true;
  }
  if (textMenuKeys("textmenu.referral").includes(text)) {
    const { html: referral, link } = referralText(ctx, chatId, lang);
    await showScreen(chatId, referral, [
      [{ text: t(lang, "referral.invite"), url: `https://t.me/share/url?url=${
        encodeURIComponent(link)
      }` }],
    ]);
    return true;
  }
  if (textMenuKeys("textmenu.home").includes(text)) {
    await showMainMenu(ctx, chatId, name, chatId === ADMIN_ID, lang);
    return true;
  }
  return false;
}

async function grantSubscription(targetId: number, days: number): Promise<void> {
  // Подписку выдаёт админ: продлевается запись в общей таблице subscriptions.
  const account = await getAccount(targetId);
  if (!account) {
    await telegram.sendMessage(
      ADMIN_ID,
      `❌ У пользователя <code>${targetId}</code> нет привязанного Google-аккаунта. Пусть сначала войдёт через Google.`,
    );
    return;
  }
  const current = await getSubscription(account.user_id);
  const base = current?.paid_until ? Date.parse(current.paid_until) : 0;
  const start = Number.isFinite(base) && base > Date.now() ? base : Date.now();
  const expiry = new Date(start + days * 86_400_000).toISOString();
  await supabase().from("subscriptions").upsert({
    user_id: account.user_id,
    email: account.email,
    tg_id: targetId,
    is_active: true,
    plan: "premium",
    paid_until: expiry,
    updated_at: nowIso(),
  }, { onConflict: "user_id" });
  await telegram.sendMessage(
    ADMIN_ID,
    `✅ Подписка выдана <code>${targetId}</code> на ${days} дней (до ${expiry.slice(0, 10)}).`,
  );
  const userLang = langOf(await getUser(targetId)) ?? "ru";
  await telegram.sendMessage(
    targetId,
    `🎉 ${t(userLang, "sub.granted", { days: t(userLang, "common.days", { days }) })}\n\n🗓 ${expiry.slice(0, 10)}`,
  );
  await logAdmin(ADMIN_ID, "give_sub", targetId, `${days} дней`);
}

// revokeSubscription — забирает подписку целиком или списывает дни.
//
// days = 0 — отзыв полностью. Если списание больше остатка, тоже отзываем
// полностью: отрицательного остатка быть не должно.
//
// Ключи на панелях не стираем — при повторной выдаче тот же клиент
// переиспользуется, а не создаётся второй.
async function revokeSubscription(targetId: number, days = 0): Promise<void> {
  const account = await getAccount(targetId);
  if (!account) {
    await telegram.sendMessage(
      ADMIN_ID,
      `❌ У пользователя <code>${targetId}</code> нет привязанного Google-аккаунта.`,
    );
    return;
  }
  const current = await getSubscription(account.user_id);
  const expiry = current?.paid_until ? Date.parse(current.paid_until) : 0;
  const remaining = current?.is_active && expiry > Date.now()
    ? expiry - Date.now()
    : 0;
  const daysMs = days * 86_400_000;
  const partial = days > 0 && remaining > daysMs;
  const userLang = langOf(await getUser(targetId)) ?? "ru";

  if (partial) {
    const next = new Date(expiry - daysMs).toISOString();
    const { error } = await supabase().from("subscriptions")
      .update({ paid_until: next, updated_at: nowIso() })
      .eq("user_id", account.user_id);
    if (error) {
      await telegram.sendMessage(
        ADMIN_ID,
        `❌ Не удалось изменить подписку: ${escapeHtml(error.message)}`,
      );
      return;
    }
    await telegram.sendMessage(
      ADMIN_ID,
      `✅ Списано ${days} дн. у <code>${targetId}</code>. Подписка теперь до ${
        next.slice(0, 10)
      }.`,
    );
    await telegram.sendMessage(
      targetId,
      t(userLang, "sub.shortened", { date: next.slice(0, 10) }),
    );
    await logAdmin(ADMIN_ID, "take_sub", targetId, `-${days} дней`);
    return;
  }

  const { error } = await supabase().from("subscriptions").update({
    is_active: false,
    plan: "free",
    paid_until: null,
    updated_at: nowIso(),
  }).eq("user_id", account.user_id);
  if (error) {
    await telegram.sendMessage(
      ADMIN_ID,
      `❌ Не удалось забрать подписку: ${escapeHtml(error.message)}`,
    );
    return;
  }
  await telegram.sendMessage(
    ADMIN_ID,
    `✅ Подписка забрана у <code>${targetId}</code>${
      account.email ? ` (${escapeHtml(account.email)})` : ""
    }.`,
  );
  await telegram.sendMessage(targetId, t(userLang, "sub.revoked"));
  await logAdmin(ADMIN_ID, "take_sub", targetId, account.email ?? "");
}

async function handleCommand(
  ctx: BotContext,
  chatId: number,
  text: string,
  name: string,
  username: string,
  lang: Lang,
): Promise<void> {
  const [command, ...rest] = text.split(/\s+/);
  const argument = rest.join(" ");

  if (command === "/start") {
    const refMatch = argument.match(/ref_(\d+)/);
    if (refMatch) {
      await handleReferral(ctx, chatId, Number(refMatch[1]), name, username, lang);
      return;
    }
    // Результат входа через Google: telegram-auth отвечает редиректом в чат
    // с меткой, а подробности присылает отдельным сообщением.
    if (argument === "linked") {
      // Привязка только что изменилась — старый кэш больше не верен.
      invalidateContext(chatId);
      const email = escapeHtml(ctx.account?.email ?? "Google");
      // Экран после входа: подтверждение + полное меню. Раньше здесь были
      // только кнопки меню без его текста — человек видел кнопки без
      // заголовка.
      const text = [
        t(lang, "google.linked", { email }),
        "",
        await mainMenuText(ctx, chatId, name, lang, t(lang, "google.linked.app")),
      ].join("\n");
      // Тот же экран, что и при обычном /start: прежний удаляется, новый
      // присылается снизу. Иначе меню после входа оказывалось ВЫШЕ, чем
      // строка «/start» (Телеграм прячет параметр start в дип-ссылке).
      const keyboard = await mainMenuButtons(chatId, chatId === ADMIN_ID, true, lang);
      await showScreen(chatId, text, keyboard, 0, true);
      return;
    }
    const failMatch = argument.match(/^link_failed(?:_([a-z_]+))?$/);
    if (failMatch) {
      await showSignInError(chatId, lang, failMatch[1] ?? "");
      return;
    }
    if (!ctx.account) {
      await showWelcome(chatId, lang, name);
      return;
    }
    await showMainMenu(ctx, chatId, name, chatId === ADMIN_ID, lang, true);
    return;
  }

  if (chatId !== ADMIN_ID) {
    await showMainMenu(ctx, chatId, name, false, lang, true);
    return;
  }

  switch (command) {
    case "/stats":
      await telegram.sendMessage(chatId, await adminStatsText());
      break;
    case "/broadcast": {
      if (!argument) {
        await telegram.sendMessage(chatId, "Использование: /broadcast <текст>");
        break;
      }
      await setState(chatId, "broadcast_text");
      await telegram.sendMessage(
        chatId,
        "📢 <b>Рассылка</b>\n\nОтправь текст сообщения для рассылки.",
      );
      break;
    }
    case "/ban": {
      const [target, ...reasonParts] = argument.split(/\s+/);
      const resolved = await resolveUser(target ?? "");
      if (!resolved) {
        await telegram.sendMessage(chatId, "❌ Пользователь не найден.");
        break;
      }
      await banUser(resolved, reasonParts.join(" "));
      await telegram.sendMessage(chatId, `✅ Пользователь ${resolved} заблокирован.`);
      break;
    }
    case "/unban": {
      const [target, ...reasonParts] = argument.split(/\s+/);
      const resolved = await resolveUser(target ?? "");
      if (!resolved) {
        await telegram.sendMessage(chatId, "❌ Пользователь не найден.");
        break;
      }
      await unbanUser(resolved, reasonParts.join(" "));
      await telegram.sendMessage(chatId, `✅ Пользователь ${resolved} разблокирован.`);
      break;
    }
    case "/warn": {
      const [target, ...reasonParts] = argument.split(/\s+/);
      const resolved = await resolveUser(target ?? "");
      if (!resolved) {
        await telegram.sendMessage(chatId, "❌ Пользователь не найден.");
        break;
      }
      await warnUser(resolved, reasonParts.join(" "));
      await telegram.sendMessage(chatId, `⚠️ Предупреждение выдано ${resolved}.`);
      break;
    }
    case "/sub": {
      const [target, days] = argument.split(/\s+/);
      const resolved = await resolveUser(target ?? "");
      const count = Number(days);
      if (!resolved || !Number.isFinite(count) || count <= 0 || count > 365) {
        await telegram.sendMessage(
          chatId,
          "❌ Использование: <code>/sub &lt;ID или @username&gt; &lt;дней&gt;</code>",
        );
        break;
      }
      await grantSubscription(resolved, count);
      break;
    }
    case "/unsub": {
      const [rawId, rawDays] = argument.split(/\s+/);
      const resolved = await resolveUser(rawId ?? "");
      const days = rawDays ? Number(rawDays) : 0;
      if (!resolved || !Number.isFinite(days) || days < 0) {
        await telegram.sendMessage(
          chatId,
          "❌ Использование: <code>/unsub &lt;ID|@username&gt; [дней]</code>\nБез числа дней подписка отзывается полностью.",
        );
        break;
      }
      await revokeSubscription(resolved, days);
      break;
    }
    case "/tickets":
      await telegram.sendMessage(chatId, await adminTicketsText());
      break;
    case "/ping":
      await telegram.sendMessage(
        chatId,
        `⏰ <b>Бот работает</b>\n\nВремя: ${
          new Date().toISOString().slice(0, 19).replace("T", " ")
        } UTC`,
      );
      break;
    case "/setwebhook": {
      const ok = await telegram.setWebhook(WEBHOOK_URL, TELEGRAM_SECRET);
      await telegram.sendMessage(
        chatId,
        ok ? "✅ Webhook обновлён" : "❌ Не удалось обновить webhook",
      );
      break;
    }
    case "/logout": {
      const email = await unlinkAccount(chatId);
      await telegram.sendMessage(
        chatId,
        email
          ? t(lang, "google.logout.done.short", { email: escapeHtml(email) })
          : t(lang, "google.logout.absent"),
      );
      break;
    }
    case "/cancel":
      await clearState(chatId);
      await telegram.sendMessage(chatId, t(lang, "common.cancel"));
      break;
    default:
      await showMainMenu(ctx, chatId, name, true, lang);
  }
}

// ===================== Обработка callback =====================

async function handleCallback(callback: CallbackQuery): Promise<void> {
  const chatId = callback.from?.id ?? 0;
  const data = callback.data ?? "";
  const messageId = callback.message?.message_id ?? 0;

  const isAdmin = chatId === ADMIN_ID;
  const adminOnly = data === "admin" || data === "confirm_broadcast" ||
    data.startsWith("admin_") || data.startsWith("reply_ticket:") ||
    data.startsWith("close_ticket:") || data.startsWith("warn_user:");

  // Контекст и ответ на callback идут параллельно: клиент перестаёт
  // показывать «часики», не дожидаясь базы.
  const contextPromise = loadContext(chatId, data === "sub_check");
  const answered = !isAdmin && adminOnly
    ? telegram.answerCallback(callback.id, "⛔ Недоступно", true)
    : telegram.answerCallback(callback.id);

  const ctx = await contextPromise;
  await answered;

  const user = ctx.user;
  if (user?.banned) return;
  if (!isAdmin && adminOnly) return;

  const lang = langOf(user) ?? "ru";

  // Сообщение, на котором нажали кнопку, и есть текущий экран чата.
  // Если он и так записан — базу не трогаем: это лишний круг на каждый клик.
  if (messageId > 0 && ctx.user?.screen_message_id !== messageId) {
    await setScreenId(chatId, messageId);
  }

  // Согласие с документами — до входа и до разделов. Разрешаем только
  // язык, сами кнопки согласия и документы.
  const consentAction = data === "consent_accept" ||
    data === "consent_decline" || data === "consent_back" ||
    data.startsWith("lang_") || data === "docs";
  if (!hasConsent(user) && !consentAction) {
    await renderScreen(chatId, messageId, consentText(lang), consentKeyboard(lang));
    return;
  }

  // Ворота: без привязанного Google-аккаунта доступны только вход и
  // смена языка. Остальные разделы закрыты до входа.
  const linkedAccount = ctx.account;
  if (!linkedAccount && !consentAction &&
      data !== "google_login" && data !== "welcome_back") {
    await renderScreen(
      chatId,
      messageId,
      welcomeText(lang),
      [
        [{ text: t(lang, "welcome.login"), callback_data: "google_login" }],
        [{ text: t(lang, "profile.language"), callback_data: "lang_menu|welcome" }],
        [{ text: t(lang, "menu.docs"), callback_data: "docs" }],
      ],
    );
    return;
  }

  switch (true) {
    // --- выбор языка ---
    case data.startsWith("lang_ru"):
    case data.startsWith("lang_en"): {
      const [langKey, target] = data.split("|");
      const picked: Lang = langKey === "lang_ru" ? "ru" : "en";
      await setLanguage(chatId, picked);
      // Язык изменился — кэш контекста больше не актуален.
      invalidateContext(chatId);

      // Согласие важнее входа: после языка — документы.
      if (!hasConsent(user) || target === "consent") {
        await showConsent(chatId, messageId, picked);
        break;
      }

      if (!ctx.account) {
        // Без входа в Google доступно только приветствие и смена языка.
        await showWelcome(chatId, picked, user?.name ?? "", messageId);
        break;
      }
      if (target === "profile") {
        await renderScreen(
          chatId,
          messageId,
          profileText(ctx, chatId, user?.name ?? "", picked),
          await profileKeyboard(ctx, chatId, picked),
        );
      } else if (target === "settings") {
        await showSettings(chatId, messageId, picked, ctx);
      } else {
        await renderScreen(
          chatId,
          messageId,
          mainMenuText(ctx, chatId, user?.name ?? "", picked),
          await mainMenuButtons(chatId, isAdmin, true, picked),
        );
      }
      break;
    }
    case data.startsWith("lang_menu"): {
      const target = data.split("|")[1] as
        | "profile"
        | "menu"
        | "welcome"
        | "settings"
        | "consent"
        | undefined;
      await renderScreen(
        chatId,
        messageId,
        languageScreenText(),
        languageButtons(target ?? null),
      );
      break;
    }
    case data === "consent_accept": {
      await setConsent(chatId, CONSENT_VERSION);
      invalidateContext(chatId);
      const fresh = await loadContext(chatId, true);
      const freshLang = langOf(fresh.user) ?? lang;
      const freshName = fresh.user?.name ?? user?.name ?? "";
      if (fresh.account) {
        await showMainMenu(fresh, chatId, freshName, isAdmin, freshLang);
      } else {
        await showWelcome(chatId, freshLang, freshName, messageId);
      }
      break;
    }
    case data === "consent_decline": {
      await renderScreen(
        chatId,
        messageId,
        `${t(lang, "consent.declined.title")}\n\n<blockquote>${
          t(lang, "consent.declined.text", {
            license: LICENSE_URL,
            privacy: PRIVACY_URL,
          })
        }</blockquote>`,
        [
          [{
            text: t(lang, "consent.accept"),
            callback_data: "consent_accept",
          }],
          [{
            text: t(lang, "consent.change_lang"),
            callback_data: "lang_menu|consent",
          }],
        ],
      );
      break;
    }
    case data === "consent_back": {
      await showConsent(chatId, messageId, lang);
      break;
    }
    case data === "welcome_back": {
      await showWelcome(chatId, lang, user?.name ?? "", messageId);
      break;
    }
    case data === "welcome_skip": {
      // Кнопки «Пока без входа» больше нет: без входа доступно только
      // приветствие. На всякий случай возвращаем приветствие.
      await renderScreen(
        chatId,
        messageId,
        welcomeText(lang),
        [
          [{ text: t(lang, "welcome.login"), callback_data: "google_login" }],
          [{ text: t(lang, "profile.language"), callback_data: "lang_menu|welcome" }],
          [{ text: t(lang, "menu.docs"), callback_data: "docs" }],
        ],
      );
      break;
    }
    case data === "profile":
      await showProfile(ctx, chatId, messageId, user?.name ?? "", lang);
      break;
    case data === "profile_payments":
      await showPayments(chatId, messageId, lang, ctx);
      break;
    case data === "settings":
      await showSettings(chatId, messageId, lang, ctx);
      break;
    case data === "docs":
      await showDocs(chatId, messageId, lang);
      break;
    case data === "profile_refresh": {
      // Перечитываем подписку из базы: кэш мог устареть (например, после оплаты).
      const fresh = await loadContext(chatId, true);
      await showProfile(fresh, chatId, messageId, user?.name ?? "", lang);
      break;
    }
    case data === "back" || data.startsWith("back:"):
      // «Назад» возвращает туда, откуда пришли: из профиля — в профиль,
      // из главного меню — в главное меню.
      if (data === "back:profile") {
        await showProfile(ctx, chatId, messageId, user?.name ?? "", lang);
      } else {
        await renderScreen(
          chatId,
          messageId,
          mainMenuText(ctx, chatId, user?.name ?? "", lang),
          await mainMenuButtons(chatId, isAdmin, Boolean(ctx.account), lang),
        );
      }
      break;
    case data === "connect" || data.startsWith("connect:"):
      await showConnect(ctx, chatId, messageId, lang, originOf(data));
      break;
    case data === "sub" || data.startsWith("sub:"):
      await showSubscription(ctx, chatId, messageId, lang, originOf(data));
      break;
    case data === "sub_check" || data.startsWith("sub_check:"):
      await checkSubscription(ctx, chatId, messageId, lang, originOf(data));
      break;
    case data.startsWith("plan:"): {
      const parts = data.split(":");
      const amount = Number(parts[1]);
      if (!Number.isFinite(amount) || amount <= 0) break;
      await showPayment(
        ctx,
        chatId,
        messageId,
        lang,
        amount,
        parts[2] === "profile" ? "profile" : "main",
      );
      break;
    }
    case data === "support":
      await showSupport(chatId, messageId, lang);
      break;
    case data === "thanks":
      await showThanks(chatId, messageId, lang);
      break;
    case data.startsWith("ticket:"): {
      const ticketId = Number(data.split(":")[1]);
      if (!Number.isFinite(ticketId)) break;
      await showTicketThread(chatId, messageId, lang, ticketId);
      break;
    }
    case data.startsWith("ticket_reply:"): {
      const ticketId = Number(data.split(":")[1]);
      if (!Number.isFinite(ticketId)) break;
      // Ждём следующее сообщение пользователя — оно уйдёт в заявку.
      await setState(chatId, `ticket_reply:${ticketId}`);
      await renderScreen(
        chatId,
        messageId,
        t(lang, "support.reply.hint"),
        backButton(lang),
      );
      break;
    }
    case data.startsWith("ticket_file:"): {
      // Админ просит файл из заявки: копируем из Telegram и присылаем.
      const [, kind, fileId] = data.split(":");
      if (!fileId) break;
      const copied = await telegram.copyFile(fileId);
      if (!copied) {
        await telegram.answerCallback(callback.id, "Файл недоступен", true);
        break;
      }
      await telegram.sendFile(ADMIN_ID, copied, kind || "document", "Файл из заявки");
      await telegram.answerCallback(callback.id);
      break;
    }
    case data === "referral" || data.startsWith("referral:"):
      await showReferral(ctx, chatId, messageId, lang, originOf(data));
      break;
    case data === "google_login": {
      // Ссылка несёт id текущего сообщения: после входа функция перепишет
      // именно его — в чате не появится лишних сообщений.
      const url = await googleLoginUrl(chatId, messageId);
      if (!url) {
        await renderScreen(
          chatId,
          messageId,
          "⚠️ Не удалось начать вход. Попробуй позже.",
          backButton(lang),
        );
        break;
      }
      await renderScreen(
        chatId,
        messageId,
        t(lang, "google.start"),
        [
          [{ text: t(lang, "google.continue"), url }],
          [
            { text: t(lang, "profile.language"), callback_data: "lang_menu|welcome" },
            { text: t(lang, "common.back"), callback_data: "back" },
          ],
        ],
      );
      break;
    }
    case data === "google_logout": {
      const account = await getAccount(chatId);
      if (!account) {
        await renderScreen(
          chatId,
          messageId,
          t(lang, "google.already"),
          backButton(lang),
        );
        break;
      }
      await renderScreen(
        chatId,
        messageId,
        t(lang, "google.logout.question"),
        [
          [
            { text: "✅ Да, выйти", callback_data: "google_logout_confirm" },
            { text: t(lang, "common.back"), callback_data: "profile" },
          ],
        ],
      );
      break;
    }
    case data === "google_logout_confirm": {
      const email = await unlinkAccount(chatId);
      // После выхода показываем то же приветствие, что и при первом входе:
      // цитата + кнопка входа. Короткий экран «Готово» выглядел как
      // отдельный, путающий сценарий.
      await showWelcome(chatId, lang, user?.name ?? "", messageId);
      void email;
      break;
    }
    case data === "new_ticket":
      await setState(chatId, "ticket_draft");
      await renderScreen(
        chatId,
        messageId,
        "📩 <b>Написать в поддержку</b>\n\nОпиши свою проблему одним сообщением.",
        backButton(lang),
      );
      break;
    case data === "confirm_ticket": {
      const state = await getState(chatId);
      await clearState(chatId);
      if (state?.state === "ticket_confirm") {
        await saveTicket(
          chatId,
          String(state.data?.draft ?? ""),
          user?.name ?? "",
          user?.username ?? "",
          lang,
          messageId,
          state.data?.file_id ? String(state.data.file_id) : null,
          state.data?.file_kind ? String(state.data.file_kind) : null,
        );
      }
      break;
    }
    case data === "cancel":
      await clearState(chatId);
      await renderScreen(
        chatId,
        messageId,
        t(lang, "common.cancel"),
        backButton(lang),
      );
      break;
    case data === "admin":
      await renderScreen(
        chatId,
        messageId,
        "⚙️ <b>Панель администратора</b>\n\n" +
          "<b>Что это:</b> служебный раздел — статистика, подписки, обращения, баны и рассылка.\n\n" +
          "Выбери нужное действие:",
        adminMenuButtons(),
      );
      break;
    case data === "admin_stats":
      await renderScreen(chatId, messageId, await adminStatsText(), adminBackButtons());
      break;
    case data === "admin_tickets":
      await renderScreen(chatId, messageId, await adminTicketsText(), adminBackButtons());
      break;
    case data === "admin_bans": {
      const banned = await listBannedUsers();
      const text = banned.length === 0
        ? "🚫 <b>Забаненные</b>\n\nНикого нет."
        : `🚫 <b>Забаненные</b>\n\n${banned.map((u) => `• <code>${u.id}</code> ${u.username ? `@${escapeHtml(u.username)}` : ""}`).join("\n")}`;
      await renderScreen(chatId, messageId, text, adminBackButtons());
      break;
    }
    case data === "admin_broadcast":
      await setState(chatId, "broadcast_text");
      await renderScreen(
        chatId,
        messageId,
        "📢 <b>Рассылка</b>\n\nВведи текст сообщения для рассылки:",
        backButton(lang),
      );
      break;
    case data === "admin_give_sub":
      await setState(chatId, "admin_give_sub");
      await renderScreen(
        chatId,
        messageId,
        "🎁 <b>Выдать подписку</b>\n\nВведи <b>ID</b> (или <b>@username</b>) и <b>количество дней</b>.\nПример: <code>123456789 30</code>",
        backButton(lang),
      );
      break;
    case data === "admin_take_sub":
      await setState(chatId, "admin_take_sub");
      await renderScreen(
        chatId,
        messageId,
        "🗑 <b>Забрать подписку</b>\n\n" +
          "Введи <b>ID</b> (или <b>@username</b>) и, если нужно, <b>сколько дней списать</b>.\n\n" +
          "<code>123456789 10</code> — списать 10 дней (если осталось меньше, подписка отзовётся полностью)\n" +
          "<code>123456789</code> — забрать подписку целиком",
        backButton(lang),
      );
      break;
    case data === "confirm_take_sub": {
      const state = await getState(chatId);
      await clearState(chatId);
      if (state?.state === "admin_take_sub_confirm") {
        const target = Number(state.data?.target_id ?? 0);
        const days = Number(state.data?.days ?? 0);
        if (target) {
          await revokeSubscription(target, Number.isFinite(days) ? days : 0);
        }
      }
      await renderScreen(
        chatId,
        messageId,
        "⚙️ <b>Панель администратора</b>\n\nВыбери нужное действие:",
        adminMenuButtons(),
      );
      break;
    }
    case data === "confirm_broadcast": {
      const state = await getState(chatId);
      await clearState(chatId);
      if (state?.state === "broadcast_confirm") {
        const total = await enqueueBroadcast(chatId, String(state.data?.text ?? ""));
        if (total < 0) {
          await renderScreen(chatId, messageId, "❌ Не удалось поставить рассылку в очередь.", adminBackButtons());
          break;
        }
        await renderScreen(
          chatId,
          messageId,
          `✅ Рассылка поставлена в очередь: <b>${total}</b> получателей.`,
          adminBackButtons(),
        );
        await processBroadcast();
      }
      break;
    }
    case data.startsWith("reply_ticket:"): {
      const [, rawTicket, rawTarget] = data.split(":");
      const ticketId = Number(rawTicket);
      const target = Number(rawTarget);
      if (!Number.isFinite(target) || target <= 0) break;
      await setState(chatId, "reply", {
        target_id: target,
        ticket_id: ticketId,
      });
      await renderScreen(
        chatId,
        messageId,
        `📩 <b>Ответ тикету</b>\n\n✏️ Введи ответ пользователю <code>${target}</code>:`,
        backButton(lang),
      );
      break;
    }
    case data.startsWith("close_ticket_user:"): {
      // Пользователь закрыл вопрос прямо из ответа поддержки.
      const ticketId = Number(data.split(":")[1]);
      const ticket = ticketId > 0 ? await getTicket(ticketId) : null;
      if (!ticket || ticket.user_id !== chatId) break;
      if (ticket.status !== "done") {
        await setTicketStatus(ticketId, "done");
        const user = await getUser(chatId);
        const who = user?.username
          ? `@${escapeHtml(user.username)} (<code>${chatId}</code>)`
          : `<code>${chatId}</code>`;
        await telegram.sendMessage(
          ADMIN_ID,
          `✅ <b>Заявка #${ticketId} закрыта</b>\n\nЗакрыл: ${who}`,
        );
      }
      await renderScreen(
        chatId,
        messageId,
        t(lang, "support.reply.closed", { id: ticketId }),
        backButton(lang),
      );
      break;
    }
    case data.startsWith("close_ticket:"): {
      const ticketId = Number(data.split(":")[1]);
      await setTicketStatus(ticketId, "done");
      await renderScreen(chatId, messageId, await adminTicketsText(), adminBackButtons());
      break;
    }
    case data.startsWith("warn_user:"): {
      const target = Number(data.split(":")[1]);
      await setState(chatId, "warn", { target_id: target });
      await renderScreen(
        chatId,
        messageId,
        `⚠️ Введи причину предупреждения для пользователя <code>${target}</code>:`,
        backButton(lang),
      );
      break;
    }
  }
}

// ===================== Точка входа =====================

serve(async (req) => {
  const url = new URL(req.url);

  // Крон-задача: POST с секретом.
  if (req.method === "POST" && url.searchParams.get("op") === "cron") {
    const key = req.headers.get("x-sub-key") || "";
    const allowed = [SUB_SECRET, CRON_KEY].filter(Boolean);
    if (allowed.length === 0 || !allowed.some((secret) => timingSafeEqual(secret, key))) {
      return new Response("Forbidden", { status: 403 });
    }
    await runCron();
    return new Response("pong", { status: 200 });
  }

  // Служебная страница со статусом. Раньше она была публичной и отдавала
  // URL вебхука; теперь нужен тот же ключ, что и для крона, а в ответе
  // остаётся только факт настройки.
  if (req.method === "GET") {
    const key = req.headers.get("x-sub-key") || "";
    const allowed = [SUB_SECRET, CRON_KEY].filter(Boolean);
    if (allowed.length === 0 || !allowed.some((secret) => timingSafeEqual(secret, key))) {
      return new Response("Forbidden", { status: 403 });
    }
    return new Response(
      JSON.stringify({ ok: true, secret_set: Boolean(TELEGRAM_SECRET) }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  // Старый бот и любые чужие запросы без метки — молча принимаем и
  // игнорируем: иначе они обрабатывались бы новым токеном.
  if (WEBHOOK_TAG && !timingSafeEqual(WEBHOOK_TAG, url.searchParams.get("tag") ?? "")) {
    return new Response("ok", { status: 200 });
  }

  // Проверка секрета вебхука. Без настроенного секрета функция не должна
  // принимать апдейты: иначе любой, кто знает URL, может писать от имени
  // Telegram и, например, выдать себя за администратора.
  if (!TELEGRAM_SECRET) {
    console.error("telegram-bot: TELEGRAM_SECRET_TOKEN is not configured");
    return new Response("not configured", { status: 500 });
  }
  const provided = req.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
  if (!timingSafeEqual(provided, TELEGRAM_SECRET)) {
    return new Response("forbidden", { status: 403 });
  }

  let update: TelegramUpdate;
  try {
    update = await req.json();
  } catch {
    return new Response("Bad request", { status: 400 });
  }

  try {
    if (update.callback_query) {
      await handleCallback(update.callback_query);
    } else if (update.message) {
      await handleMessage(update.message);
    }
  } catch (error) {
    console.error("telegram-bot failed:", error instanceof Error ? error.message : error);
    await telegram.sendMessage(
      ADMIN_ID,
      "🚨 <b>Ошибка обработки апдейта</b>\n\n" +
        escapeHtml(error instanceof Error ? error.message : String(error)).slice(0, 1500),
    );
  }

  // Рассылка продвигается на каждом вызове.
  try {
    await processBroadcast();
  } catch {
    // Рассылка не должна ломать ответ Telegram.
  }

  return new Response("ok", { status: 200 });
});

