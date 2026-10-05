import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Fishing Days NZ | Admin",
  description: "Fishing Days NZ administrator workspace.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return <html lang="en-NZ"><body>{children}</body></html>;
}
