import { Link } from "react-router";

import { useBookings } from "../api/queries";
import { ErrorNote, Loading } from "../components/States";
import { money, when } from "../lib/format";

export function BookingsPage() {
  const bookings = useBookings();
  if (bookings.error) return <ErrorNote error={bookings.error} />;
  if (!bookings.data) return <Loading />;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold tracking-tight">My bookings</h1>
      {bookings.data.length === 0 ? (
        <p className="text-stone-500">No bookings yet. <Link to="/" className="underline">Find an event</Link>.</p>
      ) : (
        <ul className="space-y-3">
          {bookings.data.map((b) => (
            <li key={b.id} className="card flex flex-wrap items-center justify-between gap-3" data-testid="booking">
              <div>
                <Link to={`/events/${b.eventId}`} className="font-semibold hover:underline">{b.eventTitle}</Link>
                <p className="text-sm text-stone-500">{when(b.startsAt)} · {b.venue}, {b.city}</p>
              </div>
              <div className="text-right">
                <p className="font-mono text-sm">{b.seats.join(", ")}</p>
                <p className="text-sm font-semibold">{money(b.amountPaise)}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
