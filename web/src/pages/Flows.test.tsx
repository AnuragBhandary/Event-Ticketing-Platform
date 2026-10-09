import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { auth } from "../api/session";
import type { Hold } from "../api/types";
import { renderAt } from "../test/render";
import { event, problem, server, session } from "../test/server";

const hold = (o: Partial<Hold> = {}): Hold => ({ id: "h-1", eventId: 7, seats: ["A1"], amountPaise: 250_000, status: "ACTIVE",
  expiresAt: new Date(Date.now() + 300_000).toISOString(), orderId: "order_1", orderStatus: "CREATED", bookingId: null, ...o });

describe("paying", () => {
  it("pays on the gateway page, then waits for the webhook to confirm", async () => {
    auth.signIn(session());
    let paid = false;
    server.use(
      http.get("*/api/fake-gateway/orders/order_1", () => HttpResponse.json({ orderId: "order_1", amountPaise: 250_000, status: "CREATED", merchant: "Showtime" })),
      http.post("*/api/fake-gateway/orders/order_1/pay", () => {
        paid = true;
        return HttpResponse.json({ paymentId: "pay_1", deliveries: ["{\"result\":\"booked\"}"] });
      }),
      http.get("*/api/holds/h-1", () => HttpResponse.json(paid ? hold({ status: "CONFIRMED", orderStatus: "PAID", bookingId: "b-1" }) : hold())),
    );
    const { router } = renderAt("/pay/order_1?hold=h-1");
    expect(await screen.findByTestId("pay-amount")).toHaveTextContent("₹2,500");
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹2,500" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/holds/h-1"));
    expect(await screen.findByRole("heading", { name: "You're booked" })).toBeInTheDocument();
    expect(screen.getByTestId("booked-seats")).toHaveTextContent("Seats A1 · ₹2,500");
  });

  it.each([
    [{ orderStatus: "REFUNDED", status: "EXPIRED" } as Partial<Hold>, "Those seats went to someone else", "Pick other seats"],
    [{ orderStatus: "FAILED" } as Partial<Hold>, "Payment failed", "Try again"],
    [{ orderStatus: "CREATED" } as Partial<Hold>, "Confirming your payment…", null],
  ])("shows the outcome %#", async (state, heading, action) => {
    auth.signIn(session());
    server.use(http.get("*/api/holds/h-1", () => HttpResponse.json(hold(state))));
    renderAt("/holds/h-1");
    expect(await screen.findByRole("heading", { name: heading })).toBeInTheDocument();
    if (action) expect(screen.getByRole("link", { name: action })).toBeInTheDocument();
  });
});

describe("events and bookings", () => {
  it("lists events with prices and seats left", async () => {
    server.use(http.get("*/api/events", () => HttpResponse.json([event, { ...event, id: 8, title: "Sold out show", available: 0, minPricePaise: 50_000, maxPricePaise: 50_000 }])));
    renderAt("/");
    expect(await screen.findByText("8 of 8 left")).toBeInTheDocument();
    expect(screen.getByText("₹1,500 – ₹2,500")).toBeInTheDocument();
    expect(screen.getByText("Sold out")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Sold out show/ })).toHaveAttribute("href", "/events/8");
  });

  it("shows your bookings, and asks you to sign in for them", async () => {
    const { router } = renderAt("/bookings");
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/login"));

    auth.signIn(session());
    server.use(http.get("*/api/bookings", () => HttpResponse.json([{ id: "b-1", eventId: 7, eventTitle: "Arijit Singh Live", venue: "NSCI Dome",
      city: "Mumbai", startsAt: event.startsAt, seats: ["A1", "A2"], amountPaise: 500_000, paymentId: "pay_1", createdAt: event.startsAt }])));
    renderAt("/bookings");
    expect(await screen.findByTestId("booking")).toHaveTextContent("A1, A2");
  });

  it("signs you out when the API rejects your token", async () => {
    auth.signIn(session());
    server.use(http.get("*/api/bookings", () => problem(401, "expired", "unauthorized")));
    renderAt("/bookings");
    await vi.waitFor(() => expect(auth.session()).toBeNull());
  });
});
