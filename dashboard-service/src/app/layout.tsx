import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "RateForge Dashboard",
  description: "Real-time rate limiting analytics",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-gray-50 text-gray-900 antialiased">
        {/* Top nav */}
        <header className="border-b border-gray-200 bg-white px-6 py-4 shadow-sm">
          <div className="mx-auto flex max-w-7xl items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="text-xl font-bold text-indigo-600">
                RateForge
              </span>
              <span className="rounded bg-indigo-100 px-2 py-0.5 text-xs font-semibold text-indigo-600">
                Analytics
              </span>
            </div>
            <nav className="flex gap-6 text-sm font-medium text-gray-500">
              <a
                href="/dashboard"
                className="hover:text-indigo-600 transition-colors"
              >
                Overview
              </a>
              <a
                href="/dashboard/live"
                className="hover:text-indigo-600 transition-colors"
              >
                Live Feed
              </a>
            </nav>
          </div>
        </header>

        <main className="mx-auto max-w-7xl px-6 py-8">{children}</main>
      </body>
    </html>
  );
}
