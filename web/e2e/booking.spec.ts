// Two buyers, two browsers, the real stack: nginx in front of two API instances and PostgreSQL.
//
// The buyers' live seat streams are attached to different API instances, so every live update
// they see crossed instances through PostgreSQL LISTEN/NOTIFY. They race for the same seat
// (exactly one wins), the winner pays through the fake gateway and its signed webhook, and the
// loser watches the seat turn booked. Then the loser's instance is restarted and the stream
// reconnects and keeps working.

import { exec } from "node:child_process";
import { resolve } from "node:path";
import { promisify } from "node:util";

import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";

const run = promisify(exec);
const REPO = resolve(import.meta.dirname, "..", "..");
const ADMIN = { email: "admin@example.com", password: process.env.E2E_ADMIN_PASSWORD ?? "admin-password-change-me" };

async function session(api: APIRequestContext, name: string) {
  const res = await api.post("/api/auth/register", {
    data: { name, email: `${name.toLowerCase()}-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`, password: "e2e-password-123" },
  });
  expect(res.status()).toBe(201);
  return res.json();
}

async function adminToken(api: APIRequestContext): Promise<string> {
  const res = await api.post("/api/auth/login", { data: ADMIN });
  expect(res.ok()).toBe(true);
  return (await res.json()).token;
}

async function newEvent(api: APIRequestContext): Promise<number> {
  const res = await api.post("/api/events", {
    headers: { Authorization: `Bearer ${await adminToken(api)}` },
    data: { title: `E2E show ${Date.now()}`, venue: "Test Hall", city: "Mumbai", description: "", rows: 5, seatsPerRow: 8,
      startsAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      tiers: [{ fromRow: "A", toRow: "B", pricePaise: 200_000 }, { fromRow: "C", toRow: "E", pricePaise: 100_000 }] },
  });
  expect(res.status()).toBe(201);
  return (await res.json()).id;
}

/** A signed-in buyer's page on the event, live, attached to an instance other than `avoid`. */
async function buyer(browser: Browser, api: APIRequestContext, name: string, eventId: number, avoid?: string) {
  const s = await session(api, name);
  for (let attempt = 0; attempt < 8; attempt++) {
    const context = await browser.newContext();
    await context.addInitScript((value) => localStorage.setItem("showtime.session", value), JSON.stringify(s));
    const page = await context.newPage();
    await page.goto(`/events/${eventId}`);
    const status = page.getByTestId("live-status");
    await expect(status).toHaveText("Live seat map");
    const instance = (await status.getAttribute("data-instance"))!;
    if (instance !== avoid) return { page, instance };
    await context.close(); // same instance as the other buyer: reconnect until we land elsewhere
  }
  throw new Error("could not reach a second API instance");
}

const seat = (page: Page, label: string) => page.locator(`[data-seat="${label}"]`);

test("two buyers on different instances race for a seat; one books it, the other sees it live", async ({ browser, request }) => {
  const eventId = await newEvent(request);
  const asha = await buyer(browser, request, "Asha", eventId);
  const ravi = await buyer(browser, request, "Ravi", eventId, asha.instance);
  expect(ravi.instance).not.toBe(asha.instance);

  // A hold on one instance appears on the other buyer's map, via the other instance.
  await seat(asha.page, "C3").click();
  await asha.page.getByRole("button", { name: "Hold 1 seat" }).click();
  await expect(asha.page.getByRole("region", { name: "Your hold" })).toBeVisible();
  await expect(seat(ravi.page, "C3")).toHaveAttribute("data-status", "on hold");
  await asha.page.getByRole("button", { name: "Release" }).click();
  await expect(seat(ravi.page, "C3")).toHaveAttribute("data-status", "available");

  // Both pick A5 and press Hold at the same moment: exactly one wins. The loser either loses at
  // the server (409, "just taken") or sees the seat go live before its own click lands, which
  // disables the button. Both are correct; the strict 50-way server race is ConcurrencyIT.
  await seat(asha.page, "A5").click();
  await seat(ravi.page, "A5").click();
  await Promise.all([asha, ravi].map((b) =>
    b.page.getByRole("button", { name: "Hold 1 seat" }).click({ timeout: 5_000 }).catch(() => undefined)));
  const outcomes = await Promise.all([asha, ravi].map(async (b) => {
    const held = b.page.getByRole("region", { name: "Your hold" });
    const lostAtServer = b.page.getByText("Just taken by someone else: A5");
    const lostLive = b.page.getByText("A5 just went to someone else");
    await held.or(lostAtServer).or(lostLive).first().waitFor();
    return (await held.isVisible()) ? "won" : (await lostAtServer.isVisible()) ? "lost at server" : "lost live";
  }));
  expect(outcomes.filter((o) => o === "won")).toHaveLength(1);
  test.info().annotations.push({ type: "race", description: outcomes.join(" / ") });
  const won = outcomes.map((o) => o === "won");
  const [winner, loser] = won[0] ? [asha, ravi] : [ravi, asha];
  await expect(seat(loser.page, "A5")).toHaveAttribute("data-status", "on hold");

  // The winner pays; the booking is confirmed by the gateway's webhook, not the browser.
  await winner.page.getByRole("button", { name: "Pay ₹2,000" }).click();
  await expect(winner.page).toHaveURL(/\/pay\/order_/);
  await winner.page.getByRole("button", { name: "Pay ₹2,000" }).click();
  await expect(winner.page.getByRole("heading", { name: "You're booked" })).toBeVisible();
  await expect(winner.page.getByTestId("booked-seats")).toHaveText("Seats A5 · ₹2,000");
  await expect(seat(loser.page, "A5")).toHaveAttribute("data-status", "booked");

  await winner.page.getByRole("link", { name: "See my bookings" }).click();
  await expect(winner.page.getByTestId("booking")).toContainText("A5");

  // Restart the loser's instance: its stream reconnects (to the other one) and stays live.
  await run(`docker compose restart ${loser.instance}`, { cwd: REPO });
  await expect(loser.page.getByTestId("live-status")).toHaveText("Live seat map", { timeout: 60_000 });
  await winner.page.goto(`/events/${eventId}`);
  await seat(winner.page, "E8").click();
  await winner.page.getByRole("button", { name: "Hold 1 seat" }).click();
  await expect(seat(loser.page, "E8")).toHaveAttribute("data-status", "on hold");

  const summary = await (await request.get(`/api/events/${eventId}`)).json();
  expect(summary).toMatchObject({ booked: 1, held: 1 });
});

test("an admin creates an event with the form, and it goes on sale", async ({ page, request }) => {
  await page.goto("/login?next=/admin/new");
  await page.getByLabel("Email").fill(ADMIN.email);
  await page.getByLabel("Password").fill(ADMIN.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "New event" })).toBeVisible();
  const title = `Form show ${Date.now()}`;
  await page.getByLabel("Title").fill(title);
  await page.getByLabel("Venue").fill("Rangsharda");
  await page.getByLabel("Starts at").fill("2099-03-01T19:30");
  await page.getByLabel("Rows").fill("4");
  await page.getByLabel("Tier 2 to row").fill("D");
  await page.getByRole("button", { name: "Create event" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await expect(page.locator("[data-seat]")).toHaveCount(64);
  await expect(seat(page, "A1")).toHaveAccessibleName("A1, ₹2,500, available");
  await expect(seat(page, "D16")).toHaveAccessibleName("D16, ₹1,200, available");
  expect((await (await request.get("/api/events")).json()).some((e: { title: string }) => e.title === title)).toBe(true);
});
