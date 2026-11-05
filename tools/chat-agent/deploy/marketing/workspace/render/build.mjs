#!/usr/bin/env node
// Lint a post, then render its slides:  node render/build.mjs posts/<dir>/post.json
// Writes slide-NN.png (1080x1350) next to post.json, plus carousel.pdf for carousels (LinkedIn document).
import { existsSync, readFileSync, readdirSync, unlinkSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

const BANNED = [
  String.raw`\bit'?s not (just )?(about )?\w[^.?!]{0,60}[,;.]\s*it'?s\b`,
  String.raw`\bnot (just|only) [^.?!]{1,60},? but\b`,
  String.raw`\bisn'?t (just )?about\b`,
  String.raw`\b(the )?(real )?(result|catch|secret|kicker|truth|best part|twist|takeaway)\s*[?:]`,
  String.raw`\bhere'?s (the thing|the kicker)\b`,
  String.raw`\b(let that sink in|read that again)\b`,
  String.raw`\bin today'?s\b[^.]{0,30}\b(world|landscape|age|era)\b`,
  String.raw`\blet'?s dive in\b|\bdive (deep )?into\b|\bdelve\b`,
  String.raw`\b(thrilled|excited|delighted) to (announce|share)\b`,
  String.raw`\bgame[- ]changer\b|\bchanges everything\b|\brevolutionary\b`,
  String.raw`\b(unlock|unleash|supercharge|turbocharge|elevate)\w*\b`,
  String.raw`\b(tapestry|testament|realm|embark|paradigm|synergy|myriad|plethora)\b`,
  String.raw`\b(leverag(e|es|ing)|utiliz(e|es|ing)|showcas(e|es|ing)|underscor(e|es|ing))\b`,
  String.raw`\b(seamless(ly)?|robust|pivotal|cutting[- ]edge|best[- ]in[- ]class|world[- ]class)\b`,
  String.raw`\b(ever[- ]evolving|fast[- ]paced) (landscape|world)\b|\blandscape\b`,
  String.raw`\bsay (goodbye|hello) to\b|\bnext level\b|\bthe future of \w+ is (here|now)\b`,
  String.raw`\b(in conclusion|at the end of the day)\b`,
  String.raw`,\s*(ensuring|enabling|empowering)\b`,
].map((p) => new RegExp(p, "im"));
const DASHES = /[–—]|\s-\s/;
const HASHTAG = /(?<!\w)#[A-Za-z]\w*/g;
const CHANNELS = ["instagram", "linkedin"];

function* strings(slides) {
  for (const [n, s] of slides.entries()) {
    const at = `slide ${n + 1}`;
    for (const [k, v] of Object.entries(s))
      if (typeof v === "string" && !["layout", "source", "photo"].includes(k)) yield [`${at} ${k}`, v];
    for (const p of s.points ?? []) yield [`${at} point`, `${p.head ?? ""} ${p.body ?? ""}`];
    for (const t of s.tiles ?? []) yield [`${at} tile`, `${t.value ?? ""} ${t.label ?? ""}`];
    for (const side of ["left", "right"]) if (s[side]) yield [`${at} ${side}`, [s[side].head, ...s[side].items].join(" ")];
  }
}

export function lint(post, dir = ".") {
  const errs = [];
  const { text = "", format, slides = [], channels = [] } = post;
  if (!["image", "carousel"].includes(format)) return [`format must be "image" or "carousel", got ${JSON.stringify(format)}`];
  const bad = channels.filter((c) => !CHANNELS.includes(c));
  if (!channels.length || bad.length) errs.push(`channels must be a non-empty subset of ${CHANNELS.join(", ")}`);

  if (!text.trim()) errs.push("text is empty");
  if (text.length > 2200) errs.push(`caption is ${text.length} characters; Instagram allows 2,200`);
  const hook = text.trim().split("\n")[0];
  if (hook.length > 125) errs.push(`first line is ${hook.length} characters; the hook must fit in 125 (Instagram cuts it off there)`);
  if ((text.match(HASHTAG) ?? []).length > 5) errs.push("more than 5 hashtags");
  if (channels.includes("linkedin") && (text.match(HASHTAG) ?? []).length) errs.push("LinkedIn posts take no hashtags; drop them or drop the linkedin channel");

  for (const [where, s] of [["text", text], ...strings(slides)]) {
    if (DASHES.test(s)) errs.push(`${where}: dash used as punctuation; use a period, comma or colon`);
    for (const re of BANNED) { const m = s.match(re); if (m) errs.push(`${where}: AI tell "${m[0]}"`); }
  }
  for (const [n, s] of slides.entries()) {
    const words = [...strings([s])].map(([, v]) => v).join(" ").split(/\s+/).filter(Boolean).length;
    if (words > 60) errs.push(`slide ${n + 1} has ${words} words; keep each slide under 60`);
    if (s.photo && !existsSync(resolve(dir, s.photo))) errs.push(`slide ${n + 1}: photo ${s.photo} not found (path is relative to post.json)`);
  }
  if (!post.alt?.trim()) errs.push("alt text is empty: say what the image shows");
  if (post.alt?.length > 300) errs.push(`alt text is ${post.alt.length} characters; keep it under 300`);
  if (!post.sources?.length) errs.push('sources is empty; list a URL or "facts.md#<anchor>" for every claim');
  const placeholder = JSON.stringify({ ...post, buffer: undefined, approved_by: undefined }).match(/<[A-Za-z][^<>]{0,80}>/);
  if (placeholder) errs.push(`template placeholder left in the post: ${placeholder[0]}`);
  if (format === "image" && slides.length !== 1) errs.push("image posts need exactly one slide");
  if (format === "carousel") {
    if (slides.length < 2 || slides.length > 10) errs.push(`carousel has ${slides.length} slides; Instagram takes 2 to 10`);
    if (channels.includes("linkedin") && !post.title) errs.push("carousels for LinkedIn need a title (the PDF document's name)");
  }
  return errs;
}

function browserPath() {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH;
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH ?? "/ms-playwright";
  for (const d of (existsSync(root) ? readdirSync(root) : []).filter((d) => d.startsWith("chromium-")).sort().reverse())
    for (const exe of ["chrome-linux64/chrome", "chrome-linux/chrome"]) if (existsSync(join(root, d, exe))) return join(root, d, exe);
  throw new Error("No Chromium found; set CHROMIUM_PATH");
}

async function render(post, dir) {
  const { chromium } = await import("playwright-core");
  const browser = await chromium.launch({ executablePath: browserPath() });
  try {
    const page = await browser.newPage({ viewport: { width: 1080, height: 1350 } });
    await page.goto(pathToFileURL(join(HERE, "slides.html")).href);
    const slides = post.slides.map((s) => (s.photo ? { ...s, photo: pathToFileURL(resolve(dir, s.photo)).href } : s));
    await page.evaluate((spec) => { render(spec); document.title = spec.title || "homebase.id"; }, { ...post, slides });
    await page.evaluate(() => document.fonts.ready.then(() => true));
    await page.waitForLoadState("networkidle");
    if (!(await page.evaluate(() => document.fonts.check('300 64px "Montserrat Alternates"'))))
      return ["brand font did not load; check brand/fonts/"];
    const problems = await page.evaluate(() => overflows());
    for (const [i, el] of (await page.$$(".slide")).entries())
      await el.screenshot({ path: join(dir, `slide-${String(i + 1).padStart(2, "0")}.png`) });
    if (post.format === "carousel")
      await page.pdf({ path: join(dir, "carousel.pdf"), width: "1080px", height: "1350px", printBackground: true, preferCSSPageSize: true });
    return problems;
  } finally {
    await browser.close();
  }
}

async function main() {
  const [file] = process.argv.slice(2);
  if (!file) { console.error("usage: node render/build.mjs posts/<dir>/post.json"); process.exit(2); }
  const path = resolve(file), dir = dirname(path);
  const post = JSON.parse(readFileSync(path, "utf8"));
  let problems = lint(post, dir);
  if (!problems.length) {
    for (const f of readdirSync(dir)) if (/^slide-\d+\.png$|^carousel\.pdf$/.test(f)) unlinkSync(join(dir, f));
    problems = await render(post, dir);
  }
  if (problems.length) { console.log(problems.map((p) => `FAIL ${p}`).join("\n")); process.exit(1); }
  console.log("OK", readdirSync(dir).filter((f) => /\.(png|pdf)$/.test(f)).sort().join(", "));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
