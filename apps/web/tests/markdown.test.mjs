import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import Markdown from "react-markdown";
import { markdownPlugins } from "../app/markdown-plugins.ts";

const render = (content) => renderToStaticMarkup(createElement(Markdown, { remarkPlugins: markdownPlugins, skipHtml: true }, content));

test("Korean particles after punctuation inside emphasis", () => {
  assert.equal(render("캐시에 **메모리 주소의 일부(태그)**가 저장됩니다."), "<p>캐시에 <strong>메모리 주소의 일부(태그)</strong>가 저장됩니다.</p>");
  assert.equal(render("특정 **세트(set)**를 고릅니다."), "<p>특정 <strong>세트(set)</strong>를 고릅니다.</p>");
  assert.equal(render("*세트(set)*를 고릅니다."), "<p><em>세트(set)</em>를 고릅니다.</p>");
});

test("opening punctuation and nested emphasis", () => {
  assert.equal(render("이것은**(태그)**입니다."), "<p>이것은<strong>(태그)</strong>입니다.</p>");
  assert.equal(render("**태그와 *세트(set)*를** 비교합니다."), "<p><strong>태그와 <em>세트(set)</em>를</strong> 비교합니다.</p>");
});

test("inline and fenced code stay literal", () => {
  assert.equal(render("`**세트(set)**를`"), "<p><code>**세트(set)**를</code></p>");
  assert.equal(render("```text\n**세트(set)**를\n```"), '<pre><code class="language-text">**세트(set)**를\n</code></pre>');
});

test("escaped and unclosed asterisks stay literal", () => {
  assert.equal(render(String.raw`\*\*세트(set)\*\*를`), "<p>**세트(set)**를</p>");
  assert.equal(render("**세트(set)를"), "<p>**세트(set)를</p>");
});

test("links keep destinations and can contain Korean emphasis", () => {
  assert.equal(render("[**세트(set)**를](https://example.com)"), '<p><a href="https://example.com"><strong>세트(set)</strong>를</a></p>');
  assert.equal(render("[문서](https://example.com/sets_(tag))"), '<p><a href="https://example.com/sets_(tag)">문서</a></p>');
});

test("English punctuation rules remain unchanged", () => {
  assert.equal(render("**tag(set)**s"), "<p>**tag(set)**s</p>");
  assert.equal(render("**tag(set)** works"), "<p><strong>tag(set)</strong> works</p>");
});

test("GFM tables, strikethrough, lists and line breaks remain supported", () => {
  const html = render("| 항목 | 설명 |\n| --- | --- |\n| 캐시 | **세트(set)**를 선택 |\n\n- ~~옛 설명~~\n- 새 설명\n\n첫 줄\n다음 줄");
  assert.match(html, /<table>/);
  assert.match(html, /<strong>세트\(set\)<\/strong>를/);
  assert.match(html, /<del>옛 설명<\/del>/);
  assert.match(html, /<ul>/);
  assert.match(html, /<br\/>/);
});

test("HTML and unsafe URL handling remains enabled", () => {
  const html = render('<script>alert(1)</script>\n\n[위험](javascript:alert(1))');
  assert.doesNotMatch(html, /<script|javascript:/);
});
