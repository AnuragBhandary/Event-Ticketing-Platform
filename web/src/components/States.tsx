export const Loading = () => <p className="text-sm text-stone-500">Loading…</p>;

export function ErrorNote({ error }: { error: Error }) {
  return <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{error.message}</p>;
}
