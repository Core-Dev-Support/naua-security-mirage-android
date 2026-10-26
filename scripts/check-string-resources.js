async function* walk(dir) {
  for await (const e of Deno.readDir(dir)) {
    const full = dir + "/" + e.name;
    if (e.isDirectory) yield* walk(full);
    else yield { path: full, name: e.name, isFile: true };
  }
}

const targets = [];
for await (const e of walk("app/src/main/res")) {
  if (e.isDirectory) continue;
  if (!e.name.startsWith("strings")) continue;
  if (!e.name.endsWith(".xml")) continue;
  targets.push(e.path);
}
targets.sort();

const problems = [];

for (const path of targets) {
  const text = await Deno.readTextFile(path);
  const rel = path.replace(/\\/g, "/");
  const re = /<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const name = m[1];
    const body = m[2];
    const line = text.slice(0, m.index).split("\n").length;
    const why = [];

    const apostrophes = body.match(/(?<!\\)'/g);
    if (apostrophes) {
      why.push(`unescaped apostrophe x${apostrophes.length}, write it as \\'`);
    }

    if (/&(?!(?:amp|lt|gt|quot|apos|#)[a-zA-Z0-9]*;)/.test(body)) {
      why.push("bare &, write &amp;");
    }

    let specs = 0;
    let positional = 0;
    const specRe = /%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[bBhHsScCdoxXeEfgGaAtfnp]/g;
    let hit;
    while ((hit = specRe.exec(body)) !== null) {
      specs++;
      if (hit[1]) positional++;
    }
    if (specs >= 2 && positional < specs) {
      why.push(`${specs} substitutions but only ${positional} positional, each needs an index like %1$s`);
    }

    if (why.length) problems.push({ rel, line, name, why });
  }
}

console.log(`checked ${targets.length} strings file(s)`);
if (problems.length === 0) {
  console.log("no string resource problems");
} else {
  console.log("");
  console.log(`STRING RESOURCE PROBLEMS in ${problems.length} string(s):`);
  for (const p of problems) {
    console.log(`  ${p.rel}:${p.line}  ${p.name}`);
    for (const w of p.why) console.log(`      ${w}`);
  }
  Deno.exit(1);
}