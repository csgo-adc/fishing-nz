import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "CatchCheck NZ | Plan, identify, check",
  description: "Plan a New Zealand fishing trip, explore conditions, identify your catch, and check the rules.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return <html lang="en-NZ"><body>{children}</body></html>;
}
