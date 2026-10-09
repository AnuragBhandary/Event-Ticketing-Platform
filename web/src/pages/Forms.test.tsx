import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { auth } from "../api/session";
import { renderAt } from "../test/render";
import { problem, server, session } from "../test/server";
import { eventSchema, toNewEvent } from "./NewEventPage";

describe("sign in", () => {
  it("validates, signs in and returns you where you were", async () => {
    server.use(http.post("*/api/auth/login", async ({ request }) => {
      const { password } = (await request.json()) as { password: string };
      return password === "right-password" ? HttpResponse.json(session()) : problem(401, "Email or password is wrong", "bad_credentials");
    }));
    const { router } = renderAt("/login?next=/events/7");
    await userEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Enter a valid email")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Email"), "asha@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Email or password is wrong")).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText("Password"));
    await userEvent.type(screen.getByLabelText("Password"), "right-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/events/7"));
    expect(auth.session()?.user.name).toBe("Asha");
  });

  it("ignores a 'next' that leaves the site", async () => {
    server.use(http.post("*/api/auth/login", () => HttpResponse.json(session())));
    const { router } = renderAt("/login?next=https://evil.example");
    await userEvent.type(await screen.findByLabelText("Email"), "asha@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
  });

  it("creates an account with stricter password rules", async () => {
    let body: unknown;
    server.use(http.post("*/api/auth/register", async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(session(), { status: 201 });
    }));
    renderAt("/login");
    await userEvent.click(await screen.findByRole("button", { name: "New here? Create an account" }));
    await userEvent.type(screen.getByLabelText("Name"), "Asha");
    await userEvent.type(screen.getByLabelText("Email"), "asha@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "short");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText("At least 8 characters")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Password"), "-but-longer");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));
    await vi.waitFor(() => expect(body).toEqual({ name: "Asha", email: "asha@example.com", password: "short-but-longer" }));
  });
});

describe("new event (admin)", () => {
  const valid = { title: "T", venue: "V", city: "C", description: "", startsAt: "2099-01-01T19:00", rows: "4", seatsPerRow: "10",
    tiers: [{ fromRow: "A", toRow: "B", rupees: "2500" }, { fromRow: "C", toRow: "D", rupees: "999.5" }] };

  it("checks every row is priced exactly once and converts rupees to paise", () => {
    expect(toNewEvent(eventSchema.parse(valid)).tiers).toEqual([
      { fromRow: "A", toRow: "B", pricePaise: 250_000 }, { fromRow: "C", toRow: "D", pricePaise: 99_950 }]);
    const gap = eventSchema.safeParse({ ...valid, tiers: [valid.tiers[0]] });
    expect(gap.error?.issues[0]?.message).toBe("Row C is not priced");
    const twice = eventSchema.safeParse({ ...valid, tiers: [...valid.tiers, { fromRow: "D", toRow: "D", rupees: "1" }] });
    expect(twice.error?.issues[0]?.message).toBe("Row D is priced twice");
    expect(eventSchema.safeParse({ ...valid, tiers: [{ fromRow: "A", toRow: "E", rupees: "1" }] }).error?.issues[0]?.message).toBe("Rows go up to D");
    expect(eventSchema.safeParse({ ...valid, startsAt: "2000-01-01T00:00" }).success).toBe(false);
  });

  it("is for admins only, and creates the event", async () => {
    auth.signIn(session("USER"));
    renderAt("/admin/new");
    expect(await screen.findByRole("alert")).toHaveTextContent("Only admins");
  });

  it("submits a valid event and opens it", async () => {
    auth.signIn(session("ADMIN"));
    let body: Record<string, unknown> = {};
    server.use(
      http.post("*/api/events", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ id: 42 }, { status: 201 });
      }),
      http.get("*/api/events/42", () => problem(404, "not found", "not_found")),
      http.get("*/api/events/42/seats", () => problem(404, "not found", "not_found")),
    );
    const { router } = renderAt("/admin/new");
    expect(screen.getByRole("link", { name: "New event" })).toBeInTheDocument();
    await userEvent.type(await screen.findByLabelText("Title"), "Comedy night");
    await userEvent.type(screen.getByLabelText("Venue"), "Hall");
    await userEvent.type(screen.getByLabelText("Starts at"), "2099-05-01T20:00");
    await userEvent.click(screen.getByRole("button", { name: "Create event" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/events/42"));
    expect(body).toMatchObject({ title: "Comedy night", rows: 10, seatsPerRow: 16, tiers: [{ fromRow: "A", toRow: "C", pricePaise: 250_000 }, { fromRow: "D", toRow: "J", pricePaise: 120_000 }] });
  });

  it("adds and removes price tiers", async () => {
    auth.signIn(session("ADMIN"));
    renderAt("/admin/new");
    await userEvent.click(await screen.findByRole("button", { name: "Add price tier" }));
    expect(screen.getAllByTestId("tier")).toHaveLength(3);
    await userEvent.click(screen.getAllByRole("button", { name: "Remove" })[2]!);
    expect(screen.getAllByTestId("tier")).toHaveLength(2);
  });
});
