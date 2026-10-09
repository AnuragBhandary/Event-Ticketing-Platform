// Combining the seat map snapshot (REST) with live changes (SSE).
//
// Each seat carries a version that the server increments on every change. The snapshot and the
// live changes can arrive in any order: whichever says the higher version for a seat wins. So a
// change that raced the snapshot request is never lost, and a stale one never overwrites newer
// state.

import type { Seat, SeatStatus } from "../api/types";

export interface SeatChange {
  label: string;
  status: SeatStatus;
  version: number;
}

export type Overlay = ReadonlyMap<string, SeatChange>;

/** Add changes to the overlay, keeping the highest version per seat. Returns the same map if nothing changed. */
export function applyChanges(overlay: Overlay, changes: SeatChange[]): Overlay {
  let next: Map<string, SeatChange> | null = null;
  for (const change of changes) {
    const current = (next ?? overlay).get(change.label);
    if (current && current.version >= change.version) continue;
    next ??= new Map(overlay);
    next.set(change.label, change);
  }
  return next ?? overlay;
}

export function mergeSeats(snapshot: Seat[], overlay: Overlay): Seat[] {
  if (overlay.size === 0) return snapshot;
  return snapshot.map((seat) => {
    const live = overlay.get(seat.label);
    return live && live.version > seat.version ? { ...seat, status: live.status, version: live.version } : seat;
  });
}
