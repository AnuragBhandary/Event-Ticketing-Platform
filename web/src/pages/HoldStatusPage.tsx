import { Link, useParams } from "react-router";

import { useHold } from "../api/queries";
import { ErrorNote, Loading } from "../components/States";
import { money } from "../lib/format";

/** After paying: waits for the webhook to settle the order, then shows the outcome. */
export function HoldStatusPage() {
  const { holdId = "" } = useParams();
  const hold = useHold(holdId, true);
  if (hold.error) return <ErrorNote error={hold.error} />;
  if (!hold.data) return <Loading />;
  const h = hold.data;

  let body: React.ReactNode;
  if (h.orderStatus === "PAID") {
    body = (
      <>
        <p className="text-4xl">🎟️</p>
        <h1 className="text-2xl font-bold">You're booked</h1>
        <p data-testid="booked-seats">Seats {h.seats.join(", ")} · {money(h.amountPaise)}</p>
        <Link to="/bookings" className="btn btn-primary">See my bookings</Link>
      </>
    );
  } else if (h.orderStatus === "REFUNDED") {
    body = (
      <>
        <h1 className="text-2xl font-bold">Those seats went to someone else</h1>
        <p className="text-stone-600 dark:text-stone-300">Your hold ran out before the payment arrived and the seats were taken. Your payment of {money(h.amountPaise)} has been refunded.</p>
        <Link to={`/events/${h.eventId}`} className="btn btn-primary">Pick other seats</Link>
      </>
    );
  } else if (h.orderStatus === "FAILED") {
    body = (
      <>
        <h1 className="text-2xl font-bold">Payment failed</h1>
        <p className="text-stone-600 dark:text-stone-300">Your seats are still held{h.status === "ACTIVE" ? " until the timer runs out" : ""}.</p>
        {h.status === "ACTIVE" && h.orderId && <Link to={`/pay/${h.orderId}?hold=${h.id}`} className="btn btn-primary">Try again</Link>}
      </>
    );
  } else {
    body = (
      <>
        <h1 className="text-2xl font-bold">Confirming your payment…</h1>
        <p className="text-stone-500">Waiting for the payment gateway.</p>
      </>
    );
  }
  return <section className="card mx-auto max-w-md space-y-3 text-center" aria-live="polite" data-testid="hold-outcome">{body}</section>;
}
