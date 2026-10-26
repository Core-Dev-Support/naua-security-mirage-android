import { validateKeyboard } from "./telegram.ts";

const source = await Deno.readTextFile(new URL("../index.ts", import.meta.url));

// Telegram отвергает кнопку, у которой есть только текст, — вместе со всей
// клавиатурой («Text buttons are not allowed in the inline keyboard»). Экран
// при этом молча не отрисовывается: пользователь жмёт кнопку и ничего не
// происходит. Поэтому клавиатура проверяется до отправки.

Deno.test("a button with neither callback_data nor url is rejected", () => {
  const problem = validateKeyboard([[{ text: "Выбери срок подписки:" }]]);
  if (!problem) throw new Error("a text-only button must be rejected");
  if (!problem.includes("Выбери срок подписки:")) {
    throw new Error("the error must name the offending button");
  }
});

Deno.test("a copy button is accepted", () => {
  if (
    validateKeyboard([[{ text: "📋 Скопировать ссылку", copy_text: { text: "https://t.me" } }]]) !==
      null
  ) {
    throw new Error("a copy_text button must be accepted");
  }
});

Deno.test("buttons with callback_data or url are accepted", () => {
  if (validateKeyboard([[{ text: "Оплатить", callback_data: "plan:30" }]]) !== null) {
    throw new Error("a button with callback_data must be accepted");
  }
  if (
    validateKeyboard([[{ text: "Поддержка", url: "https://t.me/News_And_Updates_Apps" }]]) !==
      null
  ) {
    throw new Error("a button with url must be accepted");
  }
});

Deno.test("an empty keyboard is accepted", () => {
  if (validateKeyboard([]) !== null) throw new Error("an empty keyboard is fine");
});

Deno.test("every inline button of the bot has a callback_data or a url", () => {
  // Исходник бота: ищем объекты кнопок, у которых нет ни callback_data,
  // ни url. Кнопки обычной (reply) клавиатуры текстовые по определению,
  // поэтому проверяем только те, что попадают в InlineKeyboard.
  const inlineBlocks = source.match(/InlineKeyboard[\s\S]{0,4000}/g) ?? [];
  for (const block of inlineBlocks) {
    const buttons = block.match(/\{[^{}]*text:[^{}]*\}/g) ?? [];
    for (const button of buttons) {
      // Объявления типов вроде { text: string; link: string } — не кнопки.
      if (/:\s*(string|number|boolean)\b/.test(button)) continue;
      // Внутренний объект copy_text: { text: link } — не кнопка.
      if (/^\{\s*text:\s*[A-Za-z_$][\w.$]*\s*\}$/.test(button.trim())) continue;
      if (
        !button.includes("callback_data") && !button.includes("url") &&
        !button.includes("copy_text")
      ) {
        throw new Error(`кнопка без действия: ${button.trim()}`);
      }
    }
  }
});
