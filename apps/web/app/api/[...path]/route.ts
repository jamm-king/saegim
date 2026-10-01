import { NextRequest } from "next/server";

const allowed = /^(settings|messages|chat|messages\/\d+\/retry|review|review\/(prepare|start|next|skip)|review\/questions\/\d+\/(answer|hint)|review\/messages\/\d+\/retry)$/;

async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const path = (await context.params).path.join("/");
  if (!allowed.test(path)) return Response.json({ detail: "경로를 찾을 수 없습니다." }, { status: 404 });
  const origin = request.headers.get("origin");
  if (request.method === "POST" && origin && new URL(origin).host !== request.headers.get("host")) {
    return Response.json({ detail: "같은 사이트에서만 질문을 보낼 수 있습니다." }, { status: 403 });
  }
  try {
    const response = await fetch(`${process.env.API_BASE_URL || "http://127.0.0.1:8080"}/api/${path}${request.nextUrl.search}`, {
      method: request.method,
      headers: { "Content-Type": "application/json" },
      body: request.method === "POST" ? await request.text() : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(75000),
    });
    return new Response(await response.text(), { status: response.status, headers: { "Content-Type": "application/json" } });
  } catch {
    return Response.json({ detail: "서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요." }, { status: 503 });
  }
}
export const GET = proxy;
export const POST = proxy;
