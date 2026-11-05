// node --test render/lint.test.mjs
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { lint } from "./build.mjs";

const example = JSON.parse(readFileSync(new URL("./example.json", import.meta.url), "utf8"));
const withText = (text) => ({ ...example, text });
const errs = (post) => lint(post).join("\n");

test("the example post passes", () => assert.deepEqual(lint(example), []));

test("catches tells, dashes, hooks and shape errors", () => {
  assert.match(errs(withText("It's not a tool, it's a teammate.")), /AI tell/);
  assert.match(errs(withText("We shipped it — finally.")), /dash/);
  assert.match(errs(withText("Thrilled to announce our new app.")), /AI tell/);
  assert.match(errs(withText("x".repeat(130))), /hook/);
  assert.match(errs(withText("Private by default.\n\n#privacy")), /LinkedIn posts take no hashtags/);
  assert.match(errs({ ...example, channels: ["instagram"], text: "Private.\n\n#a #b #c #d #e #f" }), /more than 5 hashtags/);
  assert.match(errs({ ...example, format: "text" }), /format/);
  assert.match(errs({ ...example, sources: [] }), /sources/);
  assert.match(errs({ ...example, alt: "" }), /alt/);
  assert.match(errs({ ...example, format: "image" }), /exactly one slide/);
  assert.match(errs({ ...example, slides: [example.slides[0], { ...example.slides[1], photo: "nope.jpg" }] }), /not found/);
});

test("hashtag check gives the same answer twice in a row", () => {
  const post = withText("Private by default.\n\n#privacy");
  assert.equal(errs(post), errs(post));
});

test("every template is flagged until its placeholders are filled", () => {
  const dir = new URL("../templates/", import.meta.url);
  for (const f of readdirSync(dir).filter((f) => f.endsWith(".json")))
    assert.match(lint(JSON.parse(readFileSync(new URL(f, dir), "utf8"))).join("\n"), /placeholder/, f);
});
