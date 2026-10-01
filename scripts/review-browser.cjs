const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');

// Run against the isolated real-API test web, never the normal conversation database.
(async () => {
  const base = process.env.REVIEW_TEST_URL || 'http://127.0.0.1:3000';
  const browser = await chromium.launch();
  const context = await browser.newContext({ baseURL: base, viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(90000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  const captures = [];
  async function post(button, suffix) {
    const responsePromise = page.waitForResponse(response => response.url().endsWith(suffix) && response.request().method() === 'POST');
    await page.getByRole('button', { name: button, exact: true }).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200, `Review request failed: ${suffix}`);
    const value = await response.json();
    captures.push(value);
    return { response, value };
  }
  const preparedResponse = page.waitForResponse(response => response.url().endsWith('/api/review/prepare'));
  await page.goto(base);
  const prepared = await (await preparedResponse).json();
  if (process.env.REVIEW_RESTORE_ONLY === '1') {
    assert.equal(prepared.status, 'ACTIVE');
    assert.equal(prepared.current.status, 'ANSWERED');
    await page.getByRole('button', { name: '다음 질문', exact: true }).waitFor();
    await page.screenshot({ path: '/qa/review-desktop-final.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByLabel('대화 내역').evaluate(element => element.scrollTop = element.scrollHeight);
    await page.screenshot({ path: '/qa/review-mobile-final.png' });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    const panel = await page.getByLabel('대화 내역').boundingBox();
    const controls = await page.getByLabel('현재 복습 질문').boundingBox();
    assert.ok(panel.y + panel.height <= controls.y, 'Review controls overlap history');
    assert.deepEqual(pageErrors, []);
    console.log('Final desktop/mobile layout, restored answered state, and non-overlapping controls passed. No AI calls.');
    await browser.close(); return;
  }
  assert.equal(prepared.status, 'READY', prepared.error || 'Review must be READY in the isolated test');
  assert.ok(prepared.total >= 1 && prepared.total <= 3);
  assert.equal(prepared.current, null);
  captures.push(prepared);
  await page.screenshot({ path: '/qa/review-ready.png', fullPage: true });
  console.log(`Actual OpenAI generation passed: ${prepared.total} questions.`);
  const started = (await post('복습 시작', '/api/review/start')).value;
  assert.equal(started.review.current.status, 'ACTIVE');
  const firstId = started.review.current.id;
  const hint = (await post('힌트 보기', `/api/review/questions/${firstId}/hint`)).value;
  assert.equal(hint.review.current.status, 'ACTIVE');
  assert.equal(hint.messages.length, 2);
  console.log('Actual OpenAI hint passed.');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByLabel('복습 답변', { exact: true }).fill('이벤트 루프가 블로킹되면 다른 요청도 기다립니다. 비동기 DB와 HTTP 호출을 사용합니다.');
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile horizontal overflow');
  await page.screenshot({ path: '/qa/review-mobile.png', fullPage: true });
  const answered = await post('복습 답변 보내기', `/api/review/questions/${firstId}/answer`);
  assert.equal(answered.value.review.current.status, 'ANSWERED');
  const duplicate = await page.request.post(`/api/review/questions/${firstId}/answer`, { data: answered.response.request().postDataJSON() });
  assert.equal(duplicate.status(), 200);
  assert.equal((await duplicate.json()).messages.at(-1).id, answered.value.messages.at(-1).id);
  console.log('Actual OpenAI feedback and duplicate reuse passed.');
  await page.reload();
  await page.getByRole('button', { name: prepared.total === 1 ? '복습 마치기' : '다음 질문', exact: true }).waitFor();
  const restored = await (await page.request.get('/api/review')).json();
  assert.equal(restored.current.id, firstId); assert.equal(restored.current.status, 'ANSWERED');
  captures.push(restored);
  await page.screenshot({ path: '/qa/review-feedback.png', fullPage: true });
  const next = (await post(prepared.total === 1 ? '복습 마치기' : '다음 질문', '/api/review/next')).value;
  if (prepared.total > 1) {
    assert.equal(next.review.current.number, 2);
    const skipped = (await post('복습을 건너뛰고 대화하기', '/api/review/skip')).value;
    assert.equal(skipped.review.status, 'SKIPPED');
  } else assert.equal(next.review.status, 'COMPLETED');
  await page.getByLabel('새 질문', { exact: true }).fill('복습 이후 일반 대화 초안');
  for (const value of captures) assert.ok(!/"expectedAnswer"|"sourceMessageIds"|"sourceIds"/.test(JSON.stringify(value)), 'Private answer metadata exposed');
  assert.deepEqual(pageErrors, []);
  const result = { actualOpenAI: true, targetDate: prepared.targetDate, questions: prepared.total, generation: true, hint: true, feedback: true, idempotent: true, reloadRestored: true, mobileNoOverflow: true, returnedToChat: true, privateAnswerHidden: true, pageErrors: 0 };
  await fs.writeFile('/qa/result.json', JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
  await browser.close();
})().catch(error => { console.error(error.message); process.exitCode = 1; });
