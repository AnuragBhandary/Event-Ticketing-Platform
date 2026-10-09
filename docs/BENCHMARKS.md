# Benchmarks

## Environment

| | |
|---|---|
| Machine | MacBook Pro, Apple M5 Pro, 24 GB RAM; Docker in a Colima VM (8 vCPU, 8 GiB) |
| Stack | `docker compose`: nginx, two API instances (Java 25, Spring Boot 4.1, virtual threads, Hikari pool 20 each), PostgreSQL 17 |
| Load generator | k6 in a container on the compose network, so requests go through nginx |

Everything shares one laptop, the load generator included.

## Booking rush: 200 buyers, 60 seconds, one 1,000-seat event

`load/booking-rush.js`. Setup creates an event (25 rows × 40 seats, ₹1,000 each) and registers 200
buyers. Each buyer then loops for 60 s: pick two adjacent seats at random, try to hold them, and
if that works, check out and pay through FakePay (whose signed webhook goes back through nginx).
A short random pause follows each attempt. After the run, `load/audit.sql` checks the database.

| Result (raw: `load/results/2026-10-09-rush-200vus-60s.json`) | |
|---|---|
| Hold attempts | 59,119 (≈ 820/s) |
| Holds won / lost | 424 / 58,695 (lost = at least one seat already taken) |
| Seats booked | 848 of 1,000 (the other 152 were all isolated singles: no two free seats were adjacent, checked in SQL) |
| Hold latency, all attempts | median 0.73 ms, p95 **1.56 ms** |
| Hold latency, holds that won | median 13.8 ms, p95 256 ms |
| Booking end to end (hold, checkout, payment, webhook) | median 46 ms, p95 679 ms |
| Unexpected responses | 0 |

**Audit** (`load/results/2026-10-09-rush-200vus-60s-audit.txt`):

| seats_booked | seats_in_bookings | seats_sold_twice | booked seats without matching booking | bookings | paid orders |
|---|---|---|---|---|---|
| 848 | 848 | **0** | **0** | 424 | 424 |

**Reading the numbers.** Most attempts lose because their seats are already gone, and losing is
fast: `SKIP LOCKED` plus one status check, no waiting. Winning holds and full bookings cost more
and have a long tail. They happen mostly in the first seconds, when 200 buyers arrive at once on a
cold JVM, with about 100 concurrent requests per instance against a 20-connection pool. The median shows
the steady state. CI runs a smaller rush (100 buyers, 20 s) and fails the build if the audit finds
any seat sold twice.

## Correctness under concurrency (integration tests, real PostgreSQL)

| Test | Result |
|---|---|
| 50 buyers, one seat, released at the same instant | 1 × 201, 49 × 409 |
| 80 buyers, random overlapping 3-seat sets on 20 seats | no seat in two holds; HELD rows in the database = seats won |
| One webhook delivered 5 times concurrently | 1 booking, 4 × `duplicate`, 1 row in `webhook_events` |

## Browser end to end (Playwright)

`web/e2e/booking.spec.ts`, two buyers in separate browser contexts whose live streams are on
different API instances. They race for one seat; the winner pays; the loser sees it held, then
booked. Then the loser's instance is restarted and the stream reconnects and keeps updating.
Passed 18 runs out of 18. In a counted batch of 10, the loser was refused by the server (409) 7
times, and 3 times it saw the seat go to the winner live before its own click registered (which
disables the button). The test accepts both as correct; exactly one buyer won every time.

## Reproduce

```bash
make up
make rush                    # 200 buyers, 60 s; VUS=500 DURATION=120s make rush for more
make e2e
cd api && ./mvnw verify      # the integration tests above
```
