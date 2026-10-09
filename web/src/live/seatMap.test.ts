import { describe, expect, it } from "vitest";

import { seats } from "../test/server";
import { applyChanges, mergeSeats } from "./seatMap";

describe("live seat map", () => {
  it("keeps the highest version per seat, whatever order changes arrive in", () => {
    let overlay = applyChanges(new Map(), [{ label: "A1", status: "BOOKED", version: 2 }]);
    overlay = applyChanges(overlay, [{ label: "A1", status: "HELD", version: 1 }]); // late, older
    expect(overlay.get("A1")).toEqual({ label: "A1", status: "BOOKED", version: 2 });
  });

  it("returns the same overlay when nothing is newer (no needless re-render)", () => {
    const overlay = applyChanges(new Map(), [{ label: "A1", status: "HELD", version: 3 }]);
    expect(applyChanges(overlay, [{ label: "A1", status: "HELD", version: 3 }])).toBe(overlay);
  });

  it("lets a newer snapshot win over an older live change, and vice versa", () => {
    const overlay = applyChanges(new Map(), [
      { label: "A1", status: "HELD", version: 1 },
      { label: "B2", status: "BOOKED", version: 5 },
    ]);
    const merged = mergeSeats(seats({ A1: { status: "AVAILABLE", version: 2 } }), overlay);
    expect(merged.find((s) => s.label === "A1")?.status).toBe("AVAILABLE"); // snapshot is newer
    expect(merged.find((s) => s.label === "B2")).toMatchObject({ status: "BOOKED", version: 5 });
    const snapshot = seats();
    expect(mergeSeats(snapshot, new Map())).toBe(snapshot);
  });
});
