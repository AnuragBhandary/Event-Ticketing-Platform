import type { Seat } from "../api/types";
import { money } from "../lib/format";

interface Props {
  seats: Seat[];
  selected: ReadonlySet<string>;
  mine: ReadonlySet<string>;
  onToggle: (label: string) => void;
  disabled?: boolean;
}

// Price tiers, most expensive first, get progressively lighter tints.
const TIER_TONES = [
  "bg-violet-200 hover:bg-violet-300 dark:bg-violet-900 dark:hover:bg-violet-800",
  "bg-sky-200 hover:bg-sky-300 dark:bg-sky-900 dark:hover:bg-sky-800",
  "bg-teal-200 hover:bg-teal-300 dark:bg-teal-900 dark:hover:bg-teal-800",
  "bg-lime-200 hover:bg-lime-300 dark:bg-lime-900 dark:hover:bg-lime-800",
];

export function SeatMap({ seats, selected, mine, onToggle, disabled }: Props) {
  const prices = [...new Set(seats.map((s) => s.pricePaise))].sort((a, b) => b - a);
  const tone = (price: number) => TIER_TONES[Math.min(prices.indexOf(price), TIER_TONES.length - 1)]!;
  const rows = new Map<string, Seat[]>();
  for (const seat of seats) rows.set(seat.row, [...(rows.get(seat.row) ?? []), seat]);

  function look(seat: Seat): { cls: string; state: string } {
    if (mine.has(seat.label) && seat.status === "HELD") return { cls: "bg-emerald-500 text-white", state: "held by you" };
    if (seat.status === "BOOKED") return { cls: "bg-stone-300 text-stone-400 dark:bg-stone-700 dark:text-stone-500", state: "booked" };
    if (seat.status === "HELD") return { cls: "bg-amber-300 text-amber-900 dark:bg-amber-700 dark:text-amber-100", state: "on hold" };
    if (selected.has(seat.label)) return { cls: "bg-rose-600 text-white ring-2 ring-rose-300", state: "selected" };
    return { cls: tone(seat.pricePaise), state: "available" };
  }

  return (
    <div>
      <div className="mx-auto mb-6 w-2/3 rounded-b-3xl bg-stone-300 py-1 text-center text-xs font-semibold tracking-[0.3em] text-stone-600 dark:bg-stone-700 dark:text-stone-300">
        STAGE
      </div>
      <div className="overflow-x-auto pb-2">
        <div className="mx-auto w-max space-y-1.5" role="grid" aria-label="Seat map">
          {[...rows].map(([row, rowSeats]) => (
            <div key={row} role="row" className="flex items-center gap-1">
              <span className="w-5 text-center text-xs font-semibold text-stone-400">{row}</span>
              {rowSeats.map((seat) => {
                const { cls, state } = look(seat);
                const clickable = seat.status === "AVAILABLE" && !disabled;
                return (
                  <button
                    key={seat.label}
                    type="button"
                    role="gridcell"
                    aria-label={`${seat.label}, ${money(seat.pricePaise)}, ${state}`}
                    aria-pressed={selected.has(seat.label)}
                    data-seat={seat.label}
                    data-status={state}
                    disabled={!clickable}
                    onClick={() => onToggle(seat.label)}
                    className={`size-6 rounded-t-lg rounded-b-sm text-[10px] font-medium transition sm:size-7 ${cls} ${clickable ? "cursor-pointer" : "cursor-not-allowed"}`}
                  >
                    {seat.number}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      </div>
      <ul className="mt-4 flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs text-stone-500" aria-label="Legend">
        {prices.map((p) => (
          <li key={p} className="flex items-center gap-1.5"><span className={`size-3 rounded-sm ${tone(p)}`} />{money(p)}</li>
        ))}
        <li className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-rose-600" />Selected</li>
        <li className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-emerald-500" />Your hold</li>
        <li className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-amber-300" />On hold</li>
        <li className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-stone-300" />Booked</li>
      </ul>
    </div>
  );
}
