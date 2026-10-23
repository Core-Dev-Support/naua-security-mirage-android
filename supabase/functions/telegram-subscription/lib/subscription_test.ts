import {
  base64Url,
  buildSubscriptionBody,
  freeToken,
  parseSignedUser,
  subscriptionSignature,
  verifySignedUser,
} from "./subscription.ts";

// Контракт единой подписки: подпись, проверка, тело.
//
// Закрепляет поведение, общее для telegram-bot (подписывает) и
// telegram-subscription (проверяет). Если правила разойдутся, тест упадёт.

const SIGNING_VALUE = "unit-test-signing-value";

// Эталон подписи. Тот же тест есть в telegram-bot/lib/subscription_test.ts —
// он защищает от расхождения правил между двумя функциями.
const EXPECTED = "7f59481e1dd46403058381df0f39126b";

// Эталон подписи бесплатного узла. Тот же тест есть в telegram-bot/lib/
// subscription_test.ts — правила должны совпадать.
const FREE_EXPECTED = "51b0cfd328b27da9f48c9782153f500b";

Deno.test("подпись детерминирована для одного и того же аккаунта", async () => {
  const first = await subscriptionSignature(123456789, SIGNING_VALUE);
  const second = await subscriptionSignature(123456789, SIGNING_VALUE);
  if (first !== second) throw new Error("подпись нестабильна");
  if (first !== EXPECTED) {
    throw new Error(`подпись разошлась с telegram-bot: ${first} != ${EXPECTED}`);
  }
  if (first.length !== 32) throw new Error(`неверная длина подписи: ${first.length}`);
  if (!/^[0-9a-f]{32}$/.test(first)) throw new Error("подпись не hex");
});

Deno.test("разные аккаунты дают разные подписи", async () => {
  const a = await subscriptionSignature(1, SIGNING_VALUE);
  const b = await subscriptionSignature(2, SIGNING_VALUE);
  if (a === b) throw new Error("подписи совпали");
});

Deno.test("смена секрета меняет подпись", async () => {
  const a = await subscriptionSignature(1, SIGNING_VALUE);
  const b = await subscriptionSignature(1, "other-secret");
  if (a === b) throw new Error("секрет не влияет на подпись");
});

// Бесплатный узел открывается только по отдельной подписи free: флаг вроде
// ?free=1 мог бы дописать любой, у кого есть обычная ссылка подписки.
Deno.test("free-подпись совпадает с эталоном", async () => {
  const token = await freeToken(123456789, SIGNING_VALUE);
  if (token !== FREE_EXPECTED) {
    throw new Error(`free-подпись разошлась с telegram-bot: ${token} != ${FREE_EXPECTED}`);
  }
  if (!/^[0-9a-f]{32}$/.test(token)) throw new Error("free-подпись не hex");
});

Deno.test("free-подпись отличается от подписи подписки", async () => {
  const a = await subscriptionSignature(123456789, SIGNING_VALUE);
  const b = await freeToken(123456789, SIGNING_VALUE);
  if (a === b) throw new Error("токены совпали");
});

Deno.test("чужая free-подпись не подходит", async () => {
  const token = await freeToken(777, SIGNING_VALUE);
  const other = await freeToken(778, SIGNING_VALUE);
  if (token === other) throw new Error("подписи разных аккаунтов совпали");
});

Deno.test("подписанный параметр user принимается", async () => {
  const signature = await subscriptionSignature(777, SIGNING_VALUE);
  const verified = await verifySignedUser(`777_${signature}`, SIGNING_VALUE);
  if (verified !== 777) throw new Error(`ожидался аккаунт 777, получен ${verified}`);
});

Deno.test("подделка подписи отклоняется", async () => {
  const signature = await subscriptionSignature(777, SIGNING_VALUE);
  const forged = signature.slice(0, -1) + (signature.endsWith("a") ? "b" : "a");
  const verified = await verifySignedUser(`777_${forged}`, SIGNING_VALUE);
  if (verified !== null) throw new Error("подделка принята");
});

Deno.test("чужая подпись отклоняется", async () => {
  const signature = await subscriptionSignature(777, SIGNING_VALUE);
  const verified = await verifySignedUser(`777_${signature}`, "other-secret");
  if (verified !== null) throw new Error("чужая подпись принята");
});

Deno.test("мусорный параметр user отклоняется", async () => {
  for (const raw of ["", "_", "abc", "777", "777_short", "0_abc"]) {
    const verified = await verifySignedUser(raw, SIGNING_VALUE);
    if (verified !== null) throw new Error(`принят мусор: ${raw}`);
  }
});

// Сброс ссылки: версия входит в подпись. Старые ссылки (версия 0) должны
// продолжать работать — иначе деплой сбросил бы их у всех сразу.
Deno.test("версия 0 принимает прежнюю подпись", async () => {
  const signature = await subscriptionSignature(777, SIGNING_VALUE, 0);
  const verified = await verifySignedUser(`777_${signature}`, SIGNING_VALUE, 0);
  if (verified !== 777) throw new Error("старая ссылка перестала работать");
});

Deno.test("после сброса старая подпись отклоняется", async () => {
  const oldSignature = await subscriptionSignature(777, SIGNING_VALUE, 0);
  const verified = await verifySignedUser(`777_${oldSignature}`, SIGNING_VALUE, 1);
  if (verified !== null) throw new Error("старая подпись принята после сброса");
});

Deno.test("после сброса новая подпись принимается", async () => {
  const signature = await subscriptionSignature(777, SIGNING_VALUE, 1);
  const verified = await verifySignedUser(`777_${signature}`, SIGNING_VALUE, 1);
  if (verified !== 777) throw new Error(`ожидался 777, получен ${verified}`);
});

Deno.test("parseSignedUser достаёт аккаунт без проверки подписи", () => {
  if (parseSignedUser("777_abcdef") !== 777) throw new Error("не разобран user");
  for (const raw of ["", "_", "abc", "777", "0_abc", "-5_abc"]) {
    if (parseSignedUser(raw) !== null) throw new Error(`принят мусор: ${raw}`);
  }
});

Deno.test("тело подписки содержит метаданные и ссылки", () => {
  const body = buildSubscriptionBody(
    ["vless://uuid@a:1?x=1#one", "vless://uuid@b:2?x=1#two"],
    "2026-12-31T23:59:59.000Z",
  );
  if (!body.includes("#hide-settings: 1")) throw new Error("нет hide-settings");
  if (!body.includes("#profile-title: NAUA Security Mirage")) throw new Error("нет заголовка");
  if (!body.includes("expire=")) throw new Error("нет срока подписки");
  if (!body.includes("vless://uuid@a:1")) throw new Error("потеряна первая ссылка");
  if (!body.includes("vless://uuid@b:2")) throw new Error("потеряна вторая ссылка");
});

Deno.test("тело подписки без срока не содержит expire", () => {
  const body = buildSubscriptionBody(["vless://uuid@a:1"], null);
  if (body.includes("expire=")) throw new Error("срок подставлен без даты");
});

Deno.test("base64url без padding", () => {
  const encoded = base64Url("abc");
  if (encoded.includes("=")) throw new Error("остался padding");
  if (/[+/]/.test(encoded)) throw new Error("остались не-URL символы");
});
