"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import MessageContent from "./message-content";

type Message = { id: number; role: string; content: string; createdAt: string; status: string; kind: string; reviewQuestionId: number | null };
type Page = { messages: Message[]; nextCursor: number | null };
type Settings = { configured: boolean; model: string; mock: boolean };
type Turn = { user: Message; assistant: Message };
type Review = { targetDate: string; anchorDate: string | null; sourceStartDate: string | null; sourceEndDate: string | null; status: string; error: string | null; total: number; current: { id: number; number: number; prompt: string; status: string } | null };
type ReviewOutcome = { review: Review; messages: Message[] };
const time = (value: string) => new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));

async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api/${path}`, { method: body !== undefined ? "POST" : "GET", headers: { "Content-Type": "application/json" }, body: body !== undefined ? JSON.stringify(body) : undefined, cache: "no-store" });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || "요청을 완료하지 못했습니다. 다시 시도해 주세요.");
  }
  return response.json();
}

export default function Chat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [review, setReview] = useState<Review | null>(null);
  const pending = useRef<{ requestId: string; content: string; questionId?: number } | null>(null);
  const hintPending = useRef<{ requestId: string; questionId: number } | null>(null);
  const chatDraft = useRef("");
  const bottom = useRef<HTMLDivElement>(null);

  async function reload() {
    setLoading(true);
    setError("");
    try {
      const [page, config] = await Promise.all([api<Page>("messages"), api<Settings>("settings")]);
      setMessages(page.messages); setCursor(page.nextCursor); setSettings(config);
      setReview(await api<Review>("review/prepare", {}));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { void reload(); }, []);
  useEffect(() => {
    if (review?.status === "ACTIVE" && !loading) bottom.current?.scrollIntoView({ block: "end" });
  }, [review?.status, loading]);
  useEffect(() => {
    if (review?.status !== "GENERATING") return;
    const timer = setTimeout(() => { void api<Review>("review").then(setReview).catch(e => setError(e.message)); }, 1500);
    return () => clearTimeout(timer);
  }, [review]);

  function merge(additions: Message[]) {
    setMessages(previous => [...previous.filter(message => !additions.some(newer => newer.id === message.id)), ...additions].sort((a, b) => a.id - b.id));
  }

  async function reviewCommand(command: "prepare" | "start" | "next" | "skip") {
    if (busy) return;
    setBusy(true); setError("");
    try {
      if (command === "prepare") setReview(await api<Review>("review/prepare", {}));
      else {
        const result = await api<ReviewOutcome>(`review/${command}`, {});
        setReview(result.review); merge(result.messages);
        if (command === "start") { chatDraft.current = draft; setDraft(""); pending.current = null; }
        if (command === "next") { setDraft(result.review.status === "COMPLETED" ? chatDraft.current : ""); pending.current = null; }
        if (command === "skip") { setDraft(review?.status === "ACTIVE" ? chatDraft.current : draft); pending.current = null; }
        setTimeout(() => bottom.current?.scrollIntoView({ behavior: "smooth", block: "end" }), 50);
      }
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function hint() {
    if (busy || !review?.current || review.current.status !== "ACTIVE") return;
    setBusy(true); setError("");
    const id = review.current.id;
    if (hintPending.current?.questionId !== id) hintPending.current = { requestId: crypto.randomUUID(), questionId: id };
    try {
      const result = await api<ReviewOutcome>(`review/questions/${id}/hint`, { requestId: hintPending.current.requestId });
      setReview(result.review); merge(result.messages); hintPending.current = null;
      setTimeout(() => bottom.current?.scrollIntoView({ behavior: "smooth", block: "end" }), 50);
    } catch (e) {
      setError((e as Error).message);
      try { merge((await api<Page>("messages")).messages); } catch { /* Retry uses the same UUID. */ }
    } finally { setBusy(false); }
  }

  async function older() {
    setLoading(true);
    try {
      const page = await api<Page>(`messages?before=${cursor}`);
      setMessages(previous => [...page.messages, ...previous.filter(message => !page.messages.some(older => older.id === message.id))]); setCursor(page.nextCursor);
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }

  async function send(event?: FormEvent, retryId?: number) {
    event?.preventDefault();
    if (busy || (retryId === undefined && !draft.trim())) return;
    setBusy(true); setError("");
    try {
      const questionId = review?.status === "ACTIVE" ? review.current?.id : undefined;
      const retry = messages.find(message => message.id === retryId);
      if (retryId === undefined && (pending.current?.content !== draft.trim() || pending.current?.questionId !== questionId)) pending.current = { requestId: crypto.randomUUID(), content: draft.trim(), questionId };
      if ((retryId !== undefined && retry?.kind === "REVIEW") || (retryId === undefined && questionId !== undefined)) {
        const result = retryId !== undefined ? await api<ReviewOutcome>(`review/messages/${retryId}/retry`, {}) : await api<ReviewOutcome>(`review/questions/${questionId}/answer`, { requestId: pending.current!.requestId, content: pending.current!.content });
        setReview(result.review); merge(result.messages);
        if (retryId !== undefined) hintPending.current = null;
      } else {
        const turn = retryId !== undefined ? await api<Turn>(`messages/${retryId}/retry`, {}) : await api<Turn>("chat", { requestId: pending.current!.requestId, content: pending.current!.content });
        merge([turn.user, turn.assistant]);
      }
      if (retryId === undefined) { setDraft(""); pending.current = null; }
      setTimeout(() => bottom.current?.scrollIntoView({ behavior: "smooth", block: "end" }), 50);
    } catch (e) {
      setError((e as Error).message);
      // Fetch server truth even after a network error; the original UUID is reused on resend.
      try { const page = await api<Page>("messages"); setMessages(previous => [...previous.filter(message => !page.messages.some(newer => newer.id === message.id)), ...page.messages].sort((a, b) => a.id - b.id)); setCursor(page.nextCursor); } catch { /* Keep the draft and visible history for retry. */ }
      try { setReview(await api<Review>("review")); } catch { /* Keep the previous review view. */ }
    } finally { setBusy(false); }
  }

  const confirmed = messages.some(message => message.role === "assistant" && message.kind === "CHAT");
  const reviewing = review?.status === "ACTIVE";
  const answered = reviewing && review.current?.status === "ANSWERED";
  const sourceLabel = review?.sourceStartDate ? review.sourceStartDate === review.sourceEndDate ? `${review.sourceStartDate} 대화 복습` : `${review.sourceStartDate} ~ ${review.sourceEndDate} 대화 복습` : "최근 대화 복습";
  return <main className="mx-auto flex h-dvh max-w-3xl flex-col px-5 sm:px-8">
    <header className="shrink-0 border-b border-stone-200 py-5">
      <div className="flex items-center justify-between gap-3"><h1 className="text-2xl font-semibold tracking-tight">새김<span className="ml-3 text-sm font-normal text-stone-500">오늘의 대화</span></h1><span className="rounded-full bg-white px-3 py-1 text-xs text-stone-600">나의 작은 배움</span></div>
      <p className="mt-3 text-sm text-stone-500">궁금한 것을 묻고, 생각을 남겨 보세요.</p>
      <p className="mt-3 text-xs text-stone-500">{!settings ? "연결 상태 확인 중" : !settings.configured ? "OpenAI 키 미설정 · 서버 설정이 필요합니다" : confirmed ? `OpenAI 실제 응답 기록 있음 · ${settings.model}` : `OpenAI 키 설정됨 · ${settings.model} · 첫 응답 대기`}</p>
    </header>
    <section aria-label="대화 내역" className="min-h-0 flex-1 overflow-y-auto py-6">
      {review && !reviewing && <aside aria-label="최근 대화 복습" className="mb-6 rounded-2xl border border-[#cbdccf] bg-[#eef4ed] p-4 text-sm">
        <p className="text-xs text-stone-500">{sourceLabel}</p>
        {review.status === "READY" && <><p className="mt-2">최근 이야기에서 {review.total}개의 질문을 준비했어요. 떠올려 볼까요?</p><div className="mt-3 flex gap-4"><button disabled={busy || loading} onClick={() => void reviewCommand("start")} className="font-semibold text-[#21634c]">복습 시작</button><button disabled={busy || loading} onClick={() => void reviewCommand("skip")} className="text-stone-600">오늘은 건너뛰기</button></div></>}
        {review.status === "NO_CONVERSATION" && <p className="mt-2">최근 대화 날짜 기준 3일 범위에 복습할 미복습 대화가 없어요.</p>}
        {review.status === "EMPTY" && <p className="mt-2">최근 대화 날짜 기준 3일 범위를 살펴봤지만 회상 질문으로 만들 학습 내용이 없었어요.</p>}
        {review.status === "GENERATING" && <p role="status" className="mt-2">최근 대화에서 질문을 준비하는 중…</p>}
        {review.status === "FAILED" && <div role="alert" className="mt-2"><p>복습 질문을 만들지 못했어요. {review.error}</p><button disabled={busy || loading} onClick={() => void reviewCommand("prepare")} className="mt-3 underline underline-offset-4">다시 생성하기</button></div>}
        {review.status === "COMPLETED" && <p className="mt-2">오늘 복습을 마쳤어요. 일반 대화를 이어가세요.</p>}
        {review.status === "SKIPPED" && <p className="mt-2">오늘 복습은 건너뛰었어요. 일반 대화를 이어가세요.</p>}
      </aside>}
      {cursor && <div className="mb-6 text-center"><button onClick={older} disabled={loading || busy} className="rounded-full border border-stone-200 bg-white px-4 py-2 text-sm">이전 대화 불러오기</button></div>}
      {loading && <p role="status" className="py-6 text-center text-sm text-stone-500">대화를 불러오는 중…</p>}
      {!loading && !messages.length && <div className="py-20 text-center"><p className="text-xl">오늘은 무엇이 궁금한가요?</p><p className="mt-3 text-sm text-stone-500">짧은 질문 하나로 시작해도 좋아요.</p></div>}
      <div className="space-y-6">{messages.map(message => <article key={message.id} className={message.role === "user" ? "ml-auto max-w-[90%]" : "mr-auto max-w-[95%]"}>
        <div className="mb-2 flex items-center gap-2 text-xs text-stone-500"><span>{message.role === "user" ? "나" : message.kind === "REVIEW" ? "새김 · 복습" : "새김 · OpenAI"}</span><time dateTime={message.createdAt}>{time(message.createdAt)}</time>{message.kind === "REVIEW" && <span>복습</span>}</div>
        <div className={`min-w-0 rounded-2xl px-5 py-4 text-[15px] leading-7 ${message.role === "user" ? "bg-[#e6eee7]" : "border border-stone-200 bg-white"}`}><MessageContent content={message.content} /></div>
        {message.role === "user" && message.status !== "COMPLETE" && <div className="mt-2 flex items-center gap-3 text-xs text-stone-600"><span>{message.status === "FAILED" ? "AI 응답 실패 · 입력은 저장되었습니다" : "응답 대기 · 중단된 요청은 다시 시도할 수 있습니다"}</span><button disabled={busy || !settings?.configured || (message.kind === "REVIEW" && (!reviewing || review.current?.id !== message.reviewQuestionId || answered))} onClick={() => void send(undefined, message.id)} className="underline underline-offset-4">다시 시도</button></div>}
      </article>)}</div>
      {busy && <p role="status" className="mt-6 text-sm text-stone-500">OpenAI 응답을 기다리는 중…</p>}
      <div ref={bottom} />
    </section>
    <footer className="shrink-0 bg-[#f7f6f2] pb-5 pt-3">
      {reviewing && <div aria-label="현재 복습 질문" className="mb-3 rounded-xl border border-[#cbdccf] bg-[#eef4ed] p-3 text-sm">
        <p className="mb-1 text-xs text-stone-500">{sourceLabel}</p>
        <p className="text-xs text-stone-600">질문 {review.current?.number} / {review.total}{answered ? " · 답변 완료" : ""}</p>
        {!answered && <p className="mt-2 leading-6">{review.current?.prompt}</p>}
        <div className="mt-3 flex flex-wrap gap-4">{answered ? <button disabled={busy} onClick={() => void reviewCommand("next")} className="font-semibold text-[#21634c]">{review.current?.number === review.total ? "복습 마치기" : "다음 질문"}</button> : <button disabled={busy || !settings?.configured} onClick={() => void hint()} className="font-semibold text-[#21634c]">힌트 보기</button>}<button disabled={busy} onClick={() => void reviewCommand("skip")} className="text-stone-600">복습을 건너뛰고 대화하기</button></div>
      </div>}
      {error && <div role="alert" className="mb-3 rounded-xl bg-red-50 p-3 text-sm text-red-800">{error} <button disabled={busy || loading} onClick={() => void reload()} className="ml-2 underline">상태 새로고침</button></div>}
      <form onSubmit={send} className="rounded-2xl border border-stone-300 bg-white p-3 shadow-sm">
        <label htmlFor="question" className="sr-only">{reviewing ? "복습 답변" : "새 질문"}</label>
        <textarea id="question" value={draft} onChange={event => setDraft(event.target.value)} maxLength={6000} rows={3} placeholder={answered ? "피드백을 읽고 다음 질문으로 넘어가세요" : reviewing ? "정답을 보기 전에 기억나는 대로 적어 주세요" : "질문이나 생각을 적어 주세요"} className="w-full resize-none rounded-lg p-2 leading-6 outline-none" disabled={busy || answered} />
        <div className="flex items-center justify-between gap-3"><span className="pl-2 text-xs text-stone-400">{draft.length.toLocaleString()} / 6,000</span><button type="submit" disabled={busy || loading || answered || !draft.trim() || !settings?.configured} className="rounded-xl bg-[#21634c] px-5 py-2 text-sm text-white">{busy ? "답변 생성 중" : reviewing ? "복습 답변 보내기" : "보내기"}</button></div>
      </form>
      <p className="mt-3 text-center text-xs text-stone-500">AI 답변은 틀릴 수 있습니다. 중요한 내용은 원문과 함께 확인하세요.</p>
    </footer>
  </main>;
}
