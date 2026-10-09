import { useEffect, useState, useSyncExternalStore } from "react";
import { useNavigate, useParams } from "react-router";

import { ApiError } from "../api/http";
import { useCancelHold, useCheckout, useEvent, useHoldSeats } from "../api/queries";
import { auth } from "../api/session";
import type { Hold } from "../api/types";
import { SeatMap } from "../components/SeatMap";
import { ErrorNote, Loading } from "../components/States";
import { countdown, money, when } from "../lib/format";
import { useLiveSeats, type SourceFactory } from "../live/useLiveSeats";

const MAX_SEATS = 10;

function HoldPanel({ hold, onDone }: { hold: Hold; onDone: () => void }) {
  const navigate = useNavigate();
  const checkout = useCheckout();
  const cancel = useCancelHold();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(t);
  }, []);
  const left = Date.parse(hold.expiresAt) - now;
  return (
    <section aria-label="Your hold" className="card space-y-3 ring-2 ring-emerald-500">
      <div className="flex items-baseline justify-between">
        <h2 className="font-semibold">Seats held for you</h2>
        <p className={`font-mono text-lg tabular-nums ${left < 60_000 ? "text-red-600" : ""}`} data-testid="hold-countdown">
          {left > 0 ? countdown(left) : "expired"}
        </p>
      </div>
      <p className="text-sm">{hold.seats.join(", ")} · <span className="font-semibold">{money(hold.amountPaise)}</span></p>
      {(checkout.error ?? cancel.error) && <ErrorNote error={(checkout.error ?? cancel.error)!} />}
      <div className="flex gap-2">
        <button
          type="button"
          className="btn btn-primary flex-1"
          disabled={left <= 0 || checkout.isPending}
          onClick={() => checkout.mutate(hold.id, { onSuccess: (c) => void navigate(`/pay/${c.orderId}?hold=${hold.id}`) })}
        >
          Pay {money(hold.amountPaise)}
        </button>
        <button type="button" className="btn btn-secondary" disabled={cancel.isPending} onClick={() => cancel.mutate(hold.id, { onSuccess: onDone })}>
          Release
        </button>
      </div>
    </section>
  );
}

export function EventPage({ createSource }: { createSource?: SourceFactory }) {
  const id = Number(useParams().eventId);
  const navigate = useNavigate();
  const session = useSyncExternalStore(auth.subscribe, auth.session);
  const event = useEvent(id);
  const { seats, live, instance, error } = useLiveSeats(id, createSource);
  const holdSeats = useHoldSeats();
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [hold, setHold] = useState<Hold | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (event.error) return <ErrorNote error={event.error instanceof ApiError && event.error.status === 404 ? new Error("This event does not exist.") : event.error} />;
  if (!event.data || !seats) return error ? <ErrorNote error={error} /> : <Loading />;

  const status = new Map(seats.map((s) => [s.label, s] as const));
  // A seat someone else takes while it is selected drops out of the selection.
  const lost = [...selected].filter((l) => status.get(l)?.status !== "AVAILABLE");
  const picked = [...selected].filter((l) => status.get(l)?.status === "AVAILABLE").sort();
  const total = picked.reduce((sum, l) => sum + (status.get(l)?.pricePaise ?? 0), 0);
  const mine = new Set(hold?.seats ?? []);

  function toggle(label: string) {
    setNotice(null);
    setSelected((prev) => {
      const next = new Set([...prev].filter((l) => status.get(l)?.status === "AVAILABLE"));
      if (next.has(label)) next.delete(label);
      else if (next.size < MAX_SEATS) next.add(label);
      return next;
    });
  }

  function holdPicked() {
    if (!session) {
      void navigate(`/login?next=/events/${id}`);
      return;
    }
    holdSeats.mutate(
      { eventId: id, seats: picked },
      {
        onSuccess: (h) => {
          setHold(h);
          setSelected(new Set());
        },
        onError: (err) => {
          if (err instanceof ApiError && err.code === "seats_unavailable") {
            const taken = (err.extra.taken as string[] | undefined) ?? [];
            setNotice(`Just taken by someone else: ${taken.join(", ")}. Pick other seats.`);
            setSelected((prev) => new Set([...prev].filter((l) => !taken.includes(l))));
          }
        },
      },
    );
  }

  const e = event.data;
  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
      <section className="card min-w-0">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">{e.title}</h1>
            <p className="text-sm text-stone-500">{when(e.startsAt)} · {e.venue}, {e.city}</p>
          </div>
          <span className={`flex items-center gap-1.5 text-xs ${live ? "text-emerald-600" : "text-stone-400"}`} data-testid="live-status" data-instance={instance ?? undefined} title={instance ? `Live via ${instance}` : undefined}>
            <span className={`size-2 rounded-full ${live ? "animate-pulse bg-emerald-500" : "bg-stone-400"}`} />
            {live ? "Live seat map" : "Connecting…"}
          </span>
        </div>
        <SeatMap seats={seats} selected={new Set(picked)} mine={mine} onToggle={toggle} disabled={hold !== null} />
      </section>

      <aside className="space-y-4">
        {hold ? (
          <HoldPanel hold={hold} onDone={() => setHold(null)} />
        ) : (
          <section aria-label="Selection" className="card space-y-3">
            <h2 className="font-semibold">Your seats</h2>
            {picked.length === 0 ? (
              <p className="text-sm text-stone-500">Tap seats on the map (up to {MAX_SEATS}).</p>
            ) : (
              <p className="text-sm" data-testid="selection">{picked.join(", ")} · <span className="font-semibold">{money(total)}</span></p>
            )}
            {lost.length > 0 && <p role="status" className="text-sm text-amber-700">{lost.join(", ")} just went to someone else.</p>}
            {notice && <p role="alert" className="text-sm text-red-600">{notice}</p>}
            {holdSeats.error && !(holdSeats.error instanceof ApiError && holdSeats.error.code === "seats_unavailable") && <ErrorNote error={holdSeats.error} />}
            <button type="button" className="btn btn-primary w-full" disabled={picked.length === 0 || holdSeats.isPending} onClick={holdPicked}>
              {session ? `Hold ${picked.length || ""} seat${picked.length === 1 ? "" : "s"}`.replace("  ", " ") : "Sign in to book"}
            </button>
            <p className="text-xs text-stone-500">Held seats are yours for 10 minutes while you pay.</p>
          </section>
        )}
        {e.description && <p className="text-sm text-stone-600 dark:text-stone-300">{e.description}</p>}
      </aside>
    </div>
  );
}
