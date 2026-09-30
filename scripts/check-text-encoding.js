async function* walk(dir) {
  for await (const e of Deno.readDir(dir)) {
    const full = dir + '/' + e.name;
    if (e.isDirectory) yield* walk(full);
    else yield { path: full, name: e.name, isFile: true };
  }
}

const root = Deno.args[0] ?? 'app/src';

const JUNK = '\\u00a0\\u00b0\\u00b7\\u0091\\u0092\\u0093\\u0094\\u0095\\u0096\\u0097\\u0098\\u0099\\u009a\\u009b\\u009c\\u009d\\u009e\\u009f\\u00a1\\u00a2\\u00a3\\u00a5\\u00a6\\u00a7\\u00a9\\u00ac\\u00ae\\u00b1-\\u00b6\\u00bc-\\u00bf';
const MOJIBAKE = new RegExp(
  '[\\u0400-\\u04FF][' + JUNK + ']|[' + JUNK + '][\\u0400-\\u04FF]'
);

let checked = 0;
const bad = [];
const boms = [];

for await (const e of walk(root)) {
  if (!e.isFile) continue;
  const ext = e.name.slice(e.name.lastIndexOf('.'));
  if (!['.kt', '.kts', '.ts', '.xml', '.sh', '.yml', '.yaml', '.toml', '.pro', '.properties', '.json', '.sql'].includes(ext)) continue;
  let text;
  try { text = await Deno.readTextFile(e.path); } catch { continue; }
  checked++;
  const rel = e.path.substring(root.length + 1);

  if (text.charCodeAt(0) === 0xfeff) {
    boms.push(rel);
  }

  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (MOJIBAKE.test(lines[i])) {
      bad.push({ rel, why: `line ${i + 1}: ${lines[i].trim().slice(0, 110)}` });
      break;
    }
  }
}

console.log(`checked ${checked} source file(s) under ${root}`);
if (boms.length > 0) {
  console.log(`${boms.length} file(s) start with a BOM, which is not an error on its own`);
}
if (bad.length === 0) {
  console.log('no text corruption found');
} else {
  console.log('');
  console.log('TEXT CORRUPTION: the file was read as a legacy code page and written back.');
  for (const b of bad) console.log(`  ${b.rel}\n      ${b.why}`);
  console.log('');
  console.log('Restore the file from git and redo the edit with an editor that reads UTF-8.');
  Deno.exit(1);
}
