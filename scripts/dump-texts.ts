// Выгрузка всех текстов бота из словарей перевода.
const src = await Deno.readTextFile(
  new URL(
    "../supabase/functions/telegram-bot/lib/i18n.ts",
    import.meta.url,
  ),
);

function table(name: string): Record<string, string> {
  const marker = `const ${name}: Record<string, string> = `;
  const start = src.indexOf(marker);
  if (start < 0) throw new Error(`${name} не найдена`);
  const from = start + marker.length;
  const end = src.indexOf("\n};", from);
  // eslint-disable-next-line no-eval
  return eval("(" + src.slice(from, end + 2) + ")");
}

const ru = table("ru");
const en = table("en");

console.log(`ВСЕГО КЛЮЧЕЙ: RU ${Object.keys(ru).length}, EN ${Object.keys(en).length}\n`);

const lang = Deno.args[0] ?? "ru";
const data = lang === "en" ? en : ru;
console.log(`========== ${lang.toUpperCase()} ==========\n`);
for (const [key, value] of Object.entries(data)) {
  console.log(`[${key}]`);
  console.log(value);
  console.log("");
}
