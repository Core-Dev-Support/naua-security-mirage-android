import { hasKey, LANGS } from "./i18n.ts";

// index.ts — единственный файл, где собираются тексты экранов.
// Тесты ловят три класса ошибок, из-за которых бот выглядит сломанным:
//   1) ключ перевода есть по-русски, но забыт по-английски (английский пользователь
//      увидит сырой ключ);
//   2) строка перевода обёрнута в <b> дважды или эмодзи продублирован
//      («🆘 🆘 Поддержка», «<b><b>…</b></b>»);
//   3) ключ, который бот использует, вообще отсутствует в словаре.

const source = await Deno.readTextFile(new URL("../index.ts", import.meta.url));
const dictionarySource = await Deno.readTextFile(
  new URL("./i18n.ts", import.meta.url),
);

// Все ключи, которые бот просит у переводчика: t(<люч>, "...").
const usedKeys = new Set<string>();
for (const match of source.matchAll(/\bt\(\s*[A-Za-z_][\w.]*\s*,\s*"([^"]+)"/g)) {
  usedKeys.add(match[1]);
}

Deno.test("every key used by the bot exists in both languages", () => {
  if (usedKeys.size === 0) throw new Error("no keys found in index.ts");
  for (const key of usedKeys) {
    for (const lang of LANGS) {
      if (!hasKey(lang, key)) {
        throw new Error(`key "${key}" is missing in "${lang}"`);
      }
    }
  }
});

Deno.test("the bot does not wrap a translated string in an emoji or a bold tag", () => {
  // «🆘 <b>${t(...)}</b>» превращалось в «🆘 🆘 <b>Поддержка</b></b>».
  // Ловим именно обёртку перевода: «👤 <b>${name}</b>» — это нормально.
  const emojiWrap =
    /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}]\s*<b>\$\{\s*t\(/u;
  // «<b>${t(...)}</b>» — вложенный жирный, Telegram его ломает.
  const boldWrap = /<b>\$\{t\([\w.]+,\s*"[^"]+"\)\}<\/b>/u;
  const source2 = source;
  if (emojiWrap.test(source2)) {
    throw new Error("an emoji is added in front of a translated string");
  }
  if (boldWrap.test(source2)) {
    throw new Error("a translated string is wrapped in <b> twice");
  }
});

Deno.test("translated strings keep their tags balanced and unnested", () => {
  // Строка перевода уже содержит своё оформление — обёртка снаружи даёт
  // «<b><b>…</b></b>», который Telegram не рисует.
  const blocks = dictionarySource.split(/const (?:ru|en): Record<string, string> = \{/u);
  // blocks[1] — ru, blocks[2] — en.
  for (const block of blocks.slice(1, 3)) {
    let depth = 0;
    for (const match of block.matchAll(/<\/?b>/gu)) {
      depth += match[0] === "<b>" ? 1 : -1;
      if (depth < 0) throw new Error("a closing tag without an opening one");
      if (depth > 1) throw new Error("nested <b> tags in a translated string");
    }
    if (depth !== 0) throw new Error("an unclosed <b> tag in a translated string");
  }
});
