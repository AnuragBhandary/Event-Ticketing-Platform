import { zodResolver } from "@hookform/resolvers/zod";
import { useFieldArray, useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";

import { useCreateEvent } from "../api/queries";
import type { NewEvent } from "../api/types";
import { Field } from "../components/Field";
import { ErrorNote } from "../components/States";

const row = z.string().regex(/^[A-Z]$/, "A-Z");

export const eventSchema = z
  .object({
    title: z.string().trim().min(1, "Required").max(120),
    venue: z.string().trim().min(1, "Required").max(120),
    city: z.string().trim().min(1, "Required").max(60),
    description: z.string().max(2000),
    startsAt: z.string().refine((v) => Date.parse(v) > Date.now(), "Must be in the future"),
    rows: z.coerce.number<string>().int().min(1).max(26),
    seatsPerRow: z.coerce.number<string>().int().min(1).max(40),
    tiers: z.array(z.object({ fromRow: row, toRow: row, rupees: z.coerce.number<string>().min(1, "At least ₹1").max(100_000) })).min(1),
  })
  .superRefine((v, ctx) => {
    // Mirror the API's rule so mistakes show before submitting: every row priced exactly once.
    const priced = new Array<number>(v.rows).fill(0);
    v.tiers.forEach((t, i) => {
      const from = t.fromRow.charCodeAt(0) - 65;
      const to = t.toRow.charCodeAt(0) - 65;
      if (from > to || to >= v.rows) ctx.addIssue({ code: "custom", path: ["tiers", i, "toRow"], message: `Rows go up to ${String.fromCharCode(64 + v.rows)}` });
      for (let r = Math.max(0, from); r <= Math.min(to, v.rows - 1); r++) priced[r]!++;
    });
    const bad = priced.findIndex((n) => n !== 1);
    if (bad >= 0) ctx.addIssue({ code: "custom", path: ["tiers"], message: `Row ${String.fromCharCode(65 + bad)} is ${priced[bad] === 0 ? "not priced" : "priced twice"}` });
  });

type Input = z.input<typeof eventSchema>;
type Output = z.output<typeof eventSchema>;

export const toNewEvent = (v: Output): NewEvent => ({
  title: v.title, venue: v.venue, city: v.city, description: v.description,
  startsAt: new Date(v.startsAt).toISOString(), rows: v.rows, seatsPerRow: v.seatsPerRow,
  tiers: v.tiers.map((t) => ({ fromRow: t.fromRow, toRow: t.toRow, pricePaise: Math.round(t.rupees * 100) })),
});

export function NewEventPage() {
  const navigate = useNavigate();
  const create = useCreateEvent();
  const { register, control, handleSubmit, formState: { errors } } = useForm<Input, unknown, Output>({
    resolver: zodResolver(eventSchema),
    defaultValues: { title: "", venue: "", city: "Mumbai", description: "", startsAt: "", rows: "10", seatsPerRow: "16",
      tiers: [{ fromRow: "A", toRow: "C", rupees: "2500" }, { fromRow: "D", toRow: "J", rupees: "1200" }] },
  });
  const tiers = useFieldArray({ control, name: "tiers" });
  const err = (m?: string) => m && <span role="alert" className="text-xs text-red-600">{m}</span>;

  return (
    <form noValidate aria-label="New event" className="card max-w-2xl space-y-4"
      onSubmit={handleSubmit((v) => create.mutate(toNewEvent(v), { onSuccess: (e) => void navigate(`/events/${e.id}`) }))}>
      <h1 className="text-xl font-bold">New event</h1>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Title" error={errors.title?.message} className="sm:col-span-2"><input className="field" {...register("title")} /></Field>
        <Field label="Venue" error={errors.venue?.message}><input className="field" {...register("venue")} /></Field>
        <Field label="City" error={errors.city?.message}><input className="field" {...register("city")} /></Field>
        <Field label="Starts at" error={errors.startsAt?.message}><input type="datetime-local" className="field" {...register("startsAt")} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Rows" error={errors.rows?.message}><input type="number" className="field" {...register("rows")} /></Field>
          <Field label="Seats per row" error={errors.seatsPerRow?.message}><input type="number" className="field" {...register("seatsPerRow")} /></Field>
        </div>
        <Field label="Description" className="sm:col-span-2"><textarea rows={3} className="field" {...register("description")} /></Field>
      </div>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Prices (front rows first)</legend>
        {tiers.fields.map((f, i) => (
          <div key={f.id} className="flex flex-wrap items-center gap-2 text-sm" data-testid="tier">
            Rows <input aria-label={`Tier ${i + 1} from row`} className="field w-14" {...register(`tiers.${i}.fromRow`)} />
            to <input aria-label={`Tier ${i + 1} to row`} className="field w-14" {...register(`tiers.${i}.toRow`)} />
            ₹ <input aria-label={`Tier ${i + 1} price`} type="number" className="field w-28" {...register(`tiers.${i}.rupees`)} />
            {tiers.fields.length > 1 && <button type="button" className="text-stone-500 underline" onClick={() => tiers.remove(i)}>Remove</button>}
            {err(errors.tiers?.[i]?.toRow?.message ?? errors.tiers?.[i]?.rupees?.message ?? errors.tiers?.[i]?.fromRow?.message)}
          </div>
        ))}
        {err(errors.tiers?.root?.message ?? errors.tiers?.message)}
        <button type="button" className="btn btn-secondary" onClick={() => tiers.append({ fromRow: "A", toRow: "A", rupees: "500" })}>Add price tier</button>
      </fieldset>
      {create.error && <ErrorNote error={create.error} />}
      <button type="submit" className="btn btn-primary" disabled={create.isPending}>Create event</button>
    </form>
  );
}
