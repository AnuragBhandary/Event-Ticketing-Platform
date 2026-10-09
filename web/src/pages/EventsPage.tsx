import { Link } from "react-router";

import { useEvents } from "../api/queries";
import { ErrorNote, Loading } from "../components/States";
import { money, when } from "../lib/format";

export function EventsPage() {
  const events = useEvents();
  if (events.error) return <ErrorNote error={events.error} />;
  if (!events.data) return <Loading />;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold tracking-tight">Upcoming events</h1>
      {events.data.length === 0 ? (
        <p className="text-stone-500">Nothing on sale right now.</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {events.data.map((e) => {
            const total = e.rows * e.seatsPerRow;
            const soldOut = e.available === 0;
            return (
              <Link key={e.id} to={`/events/${e.id}`} className="card block transition hover:-translate-y-0.5 hover:shadow-md">
                <p className="text-xs font-semibold uppercase tracking-wide text-rose-600">{when(e.startsAt)}</p>
                <h2 className="mt-1 text-lg font-bold leading-tight">{e.title}</h2>
                <p className="text-sm text-stone-500">{e.venue}, {e.city}</p>
                <div className="mt-4 flex items-end justify-between">
                  <p className="text-sm">
                    {e.minPricePaise === e.maxPricePaise ? money(e.minPricePaise) : `${money(e.minPricePaise)} – ${money(e.maxPricePaise)}`}
                  </p>
                  <p className={`text-xs font-medium ${soldOut ? "text-stone-400" : e.available < total * 0.1 ? "text-amber-600" : "text-emerald-600"}`}>
                    {soldOut ? "Sold out" : `${e.available} of ${total} left`}
                  </p>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
