import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Fishdays - NZ | Admin",
  description: "Fishdays - NZ administrator workspace.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return <html lang="en-NZ"><body>{children}</body></html>;
}
