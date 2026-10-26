import { freeToken, subscriptionSignature, withFreeNodes } from "./subscription.ts";

// Подпись ссылки подписки: должна совпадать с telegram-subscription.
//
// Значение зафиксировано, поэтому расхождение правил в двух функциях будет
// поймано здесь, а не в проде. Тот же эталон закреплён в
// telegram-subscription/lib/subscription_test.ts.

const SIGNING_VALUE = "unit-test-signing-value";
const EXPECTED = "7f59481e1dd46403058381df0f39126b";

// Эталон подписи бесплатного узла: тот же в telegram-subscription/lib/
// subscription_test.ts.
const FREE_EXPECTED = "51b0cfd328b27da9f48c9782153f500b";

Deno.test("пустой секрет подписи отклоняется", async () => {
  let rejected = false;
  try {
    await subscriptionSignature(1, "");
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("подпись с пустым секретом не отклонена");
});

Deno.test("подпись бота совпадает с эталоном", async () => {
  const signature = await subscriptionSignature(123456789, SIGNING_VALUE);
  if (signature !== EXPECTED) {
    throw new Error(
      `подпись разошлась с telegram-subscription: ${signature} != ${EXPECTED}`,
    );
  }
  if (signature.length !== 32) throw new Error(`неверная длина: ${signature.length}`);
  if (!/^[0-9a-f]{32}$/.test(signature)) throw new Error("подпись не hex");
});

Deno.test("подпись стабильна между вызовами", async () => {
  const a = await subscriptionSignature(42, SIGNING_VALUE);
  const b = await subscriptionSignature(42, SIGNING_VALUE);
  if (a !== b) throw new Error("подпись нестабильна");
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
Deno.test("freeToken совпадает с эталоном", async () => {
  const token = await freeToken(123456789, SIGNING_VALUE);
  if (token !== FREE_EXPECTED) {
    throw new Error(
      `free-подпись разошлась с telegram-subscription: ${token} != ${FREE_EXPECTED}`,
    );
  }
  if (!/^[0-9a-f]{32}$/.test(token)) throw new Error("free-подпись не hex");
});

Deno.test("freeToken отличается от подписи подписки", async () => {
  const a = await subscriptionSignature(123456789, SIGNING_VALUE);
  const b = await freeToken(123456789, SIGNING_VALUE);
  if (a === b) throw new Error("токены совпали");
});

Deno.test("withFreeNodes подставляет подпись", async () => {
  const link = await withFreeNodes(
    "https://example.com/sub?user=1_abc&lang=ru",
    123456789,
    SIGNING_VALUE,
  );
  const parsed = new URL(link);
  if (parsed.searchParams.get("user") !== "1_abc") throw new Error("потерян user");
  if (parsed.searchParams.get("lang") !== "ru") throw new Error("потерян lang");
  if (parsed.searchParams.get("free") !== FREE_EXPECTED) {
    throw new Error("нет free-подписи");
  }
  if (link.indexOf("free=1") >= 0) throw new Error("остался старый флаг free=1");
});

// Сброс ссылки: версия входит в подпись. Версия 0 обязана совпадать с прежней,
// иначе после деплоя отвалились бы все действующие ссылки.
Deno.test("версия 0 сохраняет прежнюю подпись", async () => {
  const signature = await subscriptionSignature(123456789, SIGNING_VALUE, 0);
  if (signature !== EXPECTED) {
    throw new Error(`версия 0 сломала старую ссылку: ${signature}`);
  }
});

Deno.test("сброс ссылки меняет подпись", async () => {
  const v0 = await subscriptionSignature(123456789, SIGNING_VALUE, 0);
  const v1 = await subscriptionSignature(123456789, SIGNING_VALUE, 1);
  const v2 = await subscriptionSignature(123456789, SIGNING_VALUE, 2);
  if (v0 === v1 || v1 === v2 || v0 === v2) {
    throw new Error("разные версии дали одинаковую подпись");
  }
  if (!/^[0-9a-f]{32}$/.test(v1)) throw new Error("подпись версии не hex");
});
