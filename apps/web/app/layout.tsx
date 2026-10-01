import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "새김 · 오늘의 대화", description: "AI와 나눈 대화를 다시 떠올리는 작은 공간" };
export default function Layout({ children }: { children: React.ReactNode }) {
  return <html lang="ko"><body>{children}</body></html>;
}
