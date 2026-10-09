import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import type { EventSummary, Seat, Session } from "../api/types";

export const server = setupServer();

export const event: EventSummary = {
  id: 7, title: "Arijit Singh Live", venue: "NSCI Dome", city: "Mumbai", description: "Hits, live.",
  startsAt: "2026-11-01T14:30:00Z", rows: 2, seatsPerRow: 4, available: 8, held: 0, booked: 0,
  minPricePaise: 150_000, maxPricePaise: 250_000,
};

/** Rows A (₹2,500) and B (₹1,500), 4 seats each, all available at version 0. */
export function seats(overrides: Partial<Record<string, Partial<Seat>>> = {}): Seat[] {
  return ["A", "B"].flatMap((row) =>
    [1, 2, 3, 4].map((n) => ({
      label: `${row}${n}`, row, number: n, pricePaise: row === "A" ? 250_000 : 150_000, status: "AVAILABLE" as const, version: 0,
      ...overrides[`${row}${n}`],
    })),
  );
}

export const session = (role: "USER" | "ADMIN" = "USER"): Session => ({
  token: "test-token", expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  user: { id: 1, email: "asha@example.com", name: "Asha", role },
});

export function problem(status: number, detail: string, code: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({ status, detail, code, ...extra }, { status, headers: { "Content-Type": "application/problem+json" } });
}

export function eventRoutes(seatList: () => Seat[] = () => seats()) {
  server.use(
    http.get("*/api/events/7", () => HttpResponse.json(event)),
    http.get("*/api/events/7/seats", () => HttpResponse.json(seatList())),
  );
}
