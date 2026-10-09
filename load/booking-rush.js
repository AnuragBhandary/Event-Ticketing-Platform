// A ticket rush: many buyers at once go for the same 1,000 seats. Each buyer repeatedly picks
// two adjacent seats at random, tries to hold them, and pays for what it gets. Run through nginx
// against both API instances; afterwards audit.sql checks no seat was sold twice.
//
//   docker run --rm --network event-ticketing-platform_default -v "$PWD/load:/load" \
//     -e BASE=http://web grafana/k6 run /load/booking-rush.js

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:8090";
const VUS = Number(__ENV.VUS || 200);
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || "admin-password-change-me";

export const options = {
  setupTimeout: "180s",
  scenarios: {
    rush: { executor: "constant-vus", vus: VUS, duration: __ENV.DURATION || "60s" },
  },
  // Correctness always gates the run. The latency targets were set on the laptop in
  // docs/BENCHMARKS.md; shared CI runners are several times slower, so CI sets LATENCY=off.
  thresholds: Object.assign(
    { unexpected_responses: ["count==0"] },
    __ENV.LATENCY === "off" ? {} : { hold_ms: ["p(95)<300"], hold_won_ms: ["p(95)<300"] },
  ),
};

const holdMs = new Trend("hold_ms", true); // every hold attempt, won or lost
const holdWonMs = new Trend("hold_won_ms", true); // holds that got their seats
const bookingMs = new Trend("booking_ms", true); // hold + checkout + payment + webhook, end to end
const won = new Counter("holds_won");
const lost = new Counter("holds_lost");
const booked = new Counter("seats_booked");
const unexpected = new Counter("unexpected_responses");

const post = (path, body, token) =>
  http.post(`${BASE}${path}`, body === undefined ? null : JSON.stringify(body),
    { headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) } });

export function setup() {
  const admin = post("/api/auth/login", { email: "admin@example.com", password: ADMIN_PASSWORD }).json("token");
  const event = post("/api/events", {
    title: `Rush ${Date.now()}`, venue: "Load Test Arena", city: "Mumbai", description: "", rows: 25, seatsPerRow: 40,
    startsAt: new Date(Date.now() + 86_400_000).toISOString(), tiers: [{ fromRow: "A", toRow: "Y", pricePaise: 100_000 }],
  }, admin).json("id");
  if (!event) throw new Error("could not create the event (admin password?)");
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const res = post("/api/auth/register", { name: `Buyer ${i}`, email: `rush-${Date.now()}-${i}@example.com`, password: "load-test-password" });
    if (res.status !== 201) throw new Error(`register -> ${res.status}`);
    tokens.push(res.json("token"));
  }
  return { event, tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const row = String.fromCharCode(65 + Math.floor(Math.random() * 25));
  const n = 1 + Math.floor(Math.random() * 39);
  const hold = post("/api/holds", { eventId: data.event, seats: [`${row}${n}`, `${row}${n + 1}`] }, token);
  holdMs.add(hold.timings.duration);
  if (hold.status === 409) {
    lost.add(1);
  } else if (hold.status === 201) {
    won.add(1);
    holdWonMs.add(hold.timings.duration);
    const started = Date.now();
    const order = post(`/api/holds/${hold.json("id")}/checkout`, undefined, token).json("orderId");
    const pay = post(`/api/fake-gateway/orders/${order}/pay`, { outcome: "success" });
    if (check(pay, { "payment delivered": (r) => r.status === 200 && r.body.includes("booked") })) {
      booked.add(2);
      bookingMs.add(Date.now() - started + hold.timings.duration);
    }
  } else {
    unexpected.add(1);
    console.error(`hold -> ${hold.status} ${hold.body}`);
  }
  sleep(0.1 + Math.random() * 0.2);
}
