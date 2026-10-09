# Showtime: design

## 1. Requirements

- A seat is sold at most once, under any concurrency, retries or crashes. This is the one rule
  that must never break.
- Buyers get a short exclusive hold (10 minutes) while they pay. Picking seats is all or nothing.
- Payment is confirmed by the gateway (webhook), not by the browser, and is processed exactly
  once although gateways deliver at least once.
- Everyone looking at an event sees seats go as they are taken, within a second, whichever API
  instance they are connected to.
- Runs as several stateless instances behind a load balancer.

## 2. Architecture

nginx serves the built React app and load-balances `/api` across two Spring Boot instances
(`least_conn`, because live streams are long-lived). PostgreSQL holds all state. There is no
Redis and no message broker: PostgreSQL row locks give the seat guarantee, and PostgreSQL
`LISTEN`/`NOTIFY` carries live changes between instances.

| Package | Job |
|---|---|
| `events` | Events (JPA entity) and seats. `SeatRepository` has every statement that changes a seat. |
| `holds` | Holding and releasing seats; the expiry sweep. |
| `payments` | Checkout (gateway orders), the webhook, and FakePay. |
| `bookings` | Turning a paid order into a booking. |
| `live` | NOTIFY on change, LISTEN per instance, Server-Sent Events to browsers. |
| `auth`, `config` | Accounts, JWTs, security rules. |

JPA is used where it fits (users, events: plain CRUD). Seat, hold and payment state uses
`JdbcClient` with hand-written SQL, because the correctness of those paths is in the exact locking
and conditional statements, which should be visible and reviewable rather than generated.

## 3. Data model

`seats` has one row per seat per event: `status` (AVAILABLE, HELD, BOOKED), `hold_id` +
`hold_expires_at` when HELD, `booking_id` when BOOKED, and a `version` that increases on every
change. Check constraints tie the columns together (`status = 'HELD'` exactly when a hold id and
expiry are set; `status = 'BOOKED'` exactly when a booking id is set). A seat row can name only one
booking, so double selling is impossible at the schema level, not just in code.

`holds` records who holds which seats until when. `payment_orders` has one order per hold
(`UNIQUE (hold_id)`). `bookings` has one booking per hold, order and payment (all `UNIQUE`).
`webhook_events` records processed gateway event ids. Money is integer paise.

## 4. Holding seats

```
BEGIN
  release the buyer's previous ACTIVE hold on this event, if any
  SELECT ... FROM seats WHERE event_id = ? AND label = ANY(?) FOR UPDATE SKIP LOCKED
  every requested seat must be returned AND free (AVAILABLE, or HELD with expiry < now)
    otherwise 409 seats_unavailable {taken: [...]}       -- nothing is changed
  INSERT hold; UPDATE seats SET status = 'HELD', hold_id, hold_expires_at, version = version + 1
  NOTIFY seat_changes
COMMIT
```

**Why `SKIP LOCKED`.** With a plain `FOR UPDATE`, the second of two buyers racing for a seat waits
for the first to commit and then sees it held: correct but slower. And two buyers locking
overlapping sets in different orders (A1 then A2, versus A2 then A1) can deadlock. `SKIP LOCKED`
never waits: a row someone else is changing right now is simply not returned, and a missing row
means "taken". It answers at once and cannot deadlock. The cost is that a seat whose hold is being
*released* at that moment also looks taken for those milliseconds; the buyer can simply retry.

**Why not optimistic locking (`@Version`)?** It also prevents double booking, but under a rush most
attempts would read, then fail on write and retry, which is wasted work at exactly the wrong
moment. Pessimistic locks are cheap here because each transaction touches a few rows for
milliseconds.

**Why not Redis for holds?** Redis `SET NX` with TTLs is a common answer, but it creates a second
source of truth that must be kept consistent with the database's bookings. Here the hold is a
column on the same row the booking will later be written to, inside one transaction.

**Expiry needs no job.** Every query treats `HELD` with `hold_expires_at < now` as free. The sweeper
(`@Scheduled`, every 2 s) only flips such rows back to AVAILABLE and announces them. It uses
`SKIP LOCKED` and conditional updates, so it can run on every instance at once, late, or not at all
without affecting correctness. Time comes from an injected `Clock`, never the database's `now()`,
so tests move time instead of sleeping.

## 5. Paying

1. `POST /api/holds/{id}/checkout` creates the gateway order once per hold (`INSERT ... ON CONFLICT
   (hold_id) DO NOTHING`), so double clicks and retries get the same order.
2. The buyer pays on the gateway's page. Here that is FakePay; with Razorpay it would be its
   checkout, and the order would come from its Orders API.
3. The gateway calls `POST /api/payments/webhook` with an event id header and an HMAC-SHA256
   signature of the raw body. The signature is checked in constant time before parsing.
4. In one transaction: `INSERT INTO webhook_events (id) ... ON CONFLICT DO NOTHING`. If nothing was
   inserted, this event was already processed and the response is `duplicate`. A concurrent
   duplicate blocks on the unique key until the first transaction commits, then inserts nothing.
5. `BookingService.confirmPayment` locks the order (a second payment for it serialises here), then
   the hold's seats **in label order** (`ORDER BY label FOR UPDATE`; consistent order means no
   deadlocks). Each seat must still be this hold's, or free. If so: seats become BOOKED, a booking
   row is inserted, the order is PAID and the hold CONFIRMED. If someone else got a seat after the
   hold lapsed, the order is REFUNDED instead and the hold's remaining seats are released.

Because the dedupe insert and the booking share a transaction, a crash part way through rolls both
back and the gateway's retry does the work. A failed payment leaves the hold active so the buyer
can retry before it lapses. An amount mismatch is rejected (422) and rolled back, so it is visible
and retried, never silently accepted.

## 6. Live seat maps

- **Publish:** every seat change ends with `SELECT pg_notify('seat_changes', json)` in the same
  transaction. PostgreSQL delivers notifications only when that transaction commits, so a
  rolled-back hold is never announced (tested). Payloads are chunked under the 8 KB limit.
- **Listen:** each instance keeps one connection in `LISTEN seat_changes` on a dedicated thread and
  passes each notification to `SeatStreamHub`, which writes it to that event's open SSE streams.
- **Missed notifications:** if the listen connection drops, notifications sent meanwhile are lost.
  On reconnect the instance sends `resync` to all its clients, which refetch the seat map.
- **Browser:** `useLiveSeats` fetches the seat map and opens an `EventSource`. Every `hello` (sent
  on each (re)connect) triggers a refetch, which covers anything that happened before the
  subscription. Live changes go into an overlay, and for each seat the snapshot or overlay entry
  with the higher `version` wins. So the two can arrive in any order without a stale state winning.
- **Shutdown:** the hub is a lifecycle bean that stops before the web server's graceful shutdown
  and closes every stream. Open SSE responses are long-running requests; left open, they made
  graceful shutdown wait out its full timeout (found by the test suite hanging on exit). Browsers
  reconnect through nginx to an instance that is still up.

**Why SSE and not WebSockets?** Updates flow one way, server to browser. SSE is plain HTTP: it
passes through nginx with buffering off, reconnects automatically, and needs no protocol code on
the server.

## 7. Security

- Passwords: bcrypt via Spring Security's delegating encoder. Login compares against a dummy hash
  when the email is unknown, so response time does not reveal which emails have accounts.
- JWT (HS256), issued at login and validated by Spring's OAuth2 resource server; the `roles` claim
  becomes authorities. Only ADMIN can create events. Stateless, so any instance serves any request.
- The browser keeps the JWT in `localStorage`, readable by any script on the page; the token is
  short-lived and no third-party scripts are loaded. The alternative is an HttpOnly cookie, which
  then needs CSRF protection.
- Webhooks: HMAC over the raw body, constant-time comparison, unknown event types ignored.
- A sign-in `next` redirect only accepts same-site paths. nginx does not expose `/actuator`.

## 8. Failure modes

| Failure | Effect |
|---|---|
| Two buyers press Hold on one seat | One gets it; the other gets 409 with the seat named, at once |
| Buyer never pays | Hold lapses after 10 min; seats are free immediately, the sweeper announces it |
| Webhook delivered twice, or concurrently | One booking; the duplicate returns `duplicate` |
| Crash during webhook processing | Transaction rolls back; the gateway's retry processes it |
| Payment arrives after the hold lapsed | Booked if nobody took the seats, otherwise refunded |
| An API instance restarts | Its streams close; browsers reconnect via nginx and refetch the seat map |
| A listener connection drops | It reconnects and tells its clients to refetch (`resync`) |
| nginx retries a request | Only idempotent ones; a POST (hold, payment) is never re-sent by the proxy |

## 9. Limits and next steps

- Seats are picked individually; "best available N together" would be a query over the free
  seats of a row.
- One PostgreSQL. Seat locking is per row, so contention is per seat, not per event; at much larger
  scale, partition `seats` by event.
- A real gateway: Razorpay test mode would replace FakePay (Orders API at checkout, webhook signature
  with the dashboard secret); the webhook path stays the same.
- Queue-based waiting room for very large on-sales, so the database sees a controlled arrival rate.
