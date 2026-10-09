import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";

import { useSeats } from "../api/queries";
import { applyChanges, mergeSeats, type Overlay, type SeatChange } from "./seatMap";

export type SourceFactory = (url: string) => Pick<EventSource, "addEventListener" | "close"> & {
  onerror: ((ev: Event) => void) | null;
};

const browserSource: SourceFactory = (url) => new EventSource(url);

/**
 * The seat map, kept live. The server sends "hello" whenever a stream (re)connects; the snapshot
 * is refetched then, so anything that happened before the subscription, or while it was down, is
 * picked up. "resync" means the server itself may have missed notifications.
 */
export function useLiveSeats(eventId: number, createSource: SourceFactory = browserSource) {
  const seats = useSeats(eventId);
  const client = useQueryClient();
  const [overlay, setOverlay] = useState<Overlay>(() => new Map());
  const [live, setLive] = useState(false);
  const [instance, setInstance] = useState<string | null>(null);

  useEffect(() => {
    const source = createSource(`/api/events/${eventId}/seats/stream`);
    const refetch = () => void client.invalidateQueries({ queryKey: ["seats", eventId] });
    source.addEventListener("hello", (e) => {
      setLive(true);
      setInstance((JSON.parse((e as MessageEvent<string>).data) as { instance?: string }).instance ?? null);
      refetch();
    });
    source.addEventListener("seats", (e) => {
      const msg = JSON.parse((e as MessageEvent<string>).data) as { seats: SeatChange[] };
      setOverlay((prev) => applyChanges(prev, msg.seats));
    });
    source.addEventListener("resync", refetch);
    source.onerror = () => setLive(false); // EventSource reconnects by itself
    return () => source.close();
  }, [eventId, client, createSource]);

  const merged = useMemo(() => (seats.data ? mergeSeats(seats.data, overlay) : undefined), [seats.data, overlay]);
  return { seats: merged, live, instance, error: seats.error, isPending: seats.isPending };
}
