import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { auth } from "../api/session";
import { FakeEventSource } from "../test/fakeEventSource";
import { renderAt } from "../test/render";
import { eventRoutes, problem, seats, server, session } from "../test/server";

const seat = (label: string) => screen.getByRole("gridcell", { name: new RegExp(`^${label},`) });

async function openEvent() {
  const view = renderAt("/events/7");
  await screen.findByRole("heading", { name: "Arijit Singh Live" });
  return view;
}

describe("EventPage", () => {
  it("shows the seat map and keeps it live", async () => {
    let snapshot = seats({ B4: { status: "BOOKED", version: 2 } });
    eventRoutes(() => snapshot);
    await openEvent();
    expect(seat("A1")).toHaveAccessibleName("A1, ₹2,500, available");
    expect(seat("B4")).toBeDisabled();

    act(() => FakeEventSource.last.emit("hello", { instance: "api-1" }));
    expect(await screen.findByText("Live seat map")).toBeInTheDocument();
    expect(screen.getByTestId("live-status")).toHaveAttribute("data-instance", "api-1");
    act(() => FakeEventSource.last.emit("seats", { eventId: 7, seats: [{ label: "A2", status: "HELD", version: 1 }] }));
    expect(seat("A2")).toHaveAccessibleName("A2, ₹2,500, on hold");

    // A reconnect ("hello" again) refetches the snapshot, picking up anything missed meanwhile.
    snapshot = seats({ B4: { status: "BOOKED", version: 2 }, A3: { status: "BOOKED", version: 4 } });
    act(() => FakeEventSource.last.fail());
    expect(screen.getByTestId("live-status")).toHaveTextContent("Connecting…");
    act(() => FakeEventSource.last.emit("hello", { instance: "api-2" }));
    await vi.waitFor(() => expect(seat("A3")).toHaveAccessibleName("A3, ₹2,500, booked"));
    expect(seat("A2")).toHaveAccessibleName("A2, ₹2,500, on hold"); // live change kept (newer than snapshot)
  });

  it("asks you to sign in before holding", async () => {
    eventRoutes();
    const { router } = await openEvent();
    await userEvent.click(seat("A1"));
    await userEvent.click(screen.getByRole("button", { name: "Sign in to book" }));
    expect(router.state.location.pathname + router.state.location.search).toBe("/login?next=/events/7");
  });

  it("holds the picked seats, then counts down and offers to pay", async () => {
    auth.signIn(session());
    eventRoutes();
    let body: unknown;
    server.use(
      http.post("*/api/holds", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: "h-1", eventId: 7, seats: ["A1", "B2"], amountPaise: 400_000, status: "ACTIVE",
          expiresAt: new Date(Date.now() + 600_000).toISOString(), orderId: null, orderStatus: null, bookingId: null }, { status: 201 });
      }),
      http.post("*/api/holds/h-1/checkout", () => HttpResponse.json({ orderId: "order_1", amountPaise: 400_000, status: "CREATED", holdExpiresAt: "", paymentUrl: "/pay/order_1" })),
    );
    const { router } = await openEvent();
    await userEvent.click(seat("B2"));
    await userEvent.click(seat("A1"));
    expect(screen.getByTestId("selection")).toHaveTextContent("A1, B2 · ₹4,000");
    await userEvent.click(screen.getByRole("button", { name: "Hold 2 seats" }));
    expect(body).toEqual({ eventId: 7, seats: ["A1", "B2"] });

    const panel = await screen.findByRole("region", { name: "Your hold" });
    expect(within(panel).getByTestId("hold-countdown")).toHaveTextContent(/^(10:00|9:59)$/);
    act(() => FakeEventSource.last.emit("seats", { eventId: 7, seats: [{ label: "A1", status: "HELD", version: 1 }] }));
    expect(seat("A1")).toHaveAccessibleName("A1, ₹2,500, held by you");
    await userEvent.click(within(panel).getByRole("button", { name: "Pay ₹4,000" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/pay/order_1"));
    expect(router.state.location.search).toBe("?hold=h-1");
  });

  it("explains which seats were taken when the hold loses a race", async () => {
    auth.signIn(session());
    eventRoutes();
    server.use(http.post("*/api/holds", () => problem(409, "Some of these seats were just taken: A2", "seats_unavailable", { taken: ["A2"] })));
    await openEvent();
    await userEvent.click(seat("A1"));
    await userEvent.click(seat("A2"));
    await userEvent.click(screen.getByRole("button", { name: "Hold 2 seats" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Just taken by someone else: A2");
    expect(screen.getByTestId("selection")).toHaveTextContent(/^A1 ·/); // A2 dropped, A1 kept
  });

  it("drops a selected seat that someone else takes meanwhile", async () => {
    eventRoutes();
    await openEvent();
    await userEvent.click(seat("A4"));
    act(() => FakeEventSource.last.emit("seats", { eventId: 7, seats: [{ label: "A4", status: "HELD", version: 1 }] }));
    expect(screen.getByRole("status")).toHaveTextContent("A4 just went to someone else");
    expect(screen.queryByTestId("selection")).not.toBeInTheDocument();
  });

  it("says when the event does not exist", async () => {
    server.use(http.get("*/api/events/7", () => problem(404, "event not found", "not_found")),
      http.get("*/api/events/7/seats", () => problem(404, "event not found", "not_found")));
    renderAt("/events/7");
    expect(await screen.findByRole("alert")).toHaveTextContent("This event does not exist.");
  });
});
