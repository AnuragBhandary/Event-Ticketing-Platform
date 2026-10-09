import { useSyncExternalStore } from "react";
import { Link, NavLink, Outlet } from "react-router";

import { auth } from "../api/session";

const nav = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-3 py-1.5 text-sm font-medium ${isActive ? "bg-stone-900 text-white dark:bg-white dark:text-stone-900" : "text-stone-600 hover:bg-stone-100 dark:text-stone-300 dark:hover:bg-stone-800"}`;

export function Layout() {
  const session = useSyncExternalStore(auth.subscribe, auth.session);
  return (
    <div className="min-h-dvh">
      <header className="border-b border-stone-200 bg-white/80 backdrop-blur dark:border-stone-800 dark:bg-stone-900/80">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
          <Link to="/" className="text-lg font-black tracking-tight text-rose-600">showtime</Link>
          <nav aria-label="Main" className="flex gap-1">
            <NavLink to="/" end className={nav}>Events</NavLink>
            {session && <NavLink to="/bookings" className={nav}>My bookings</NavLink>}
            {session?.user.role === "ADMIN" && <NavLink to="/admin/new" className={nav}>New event</NavLink>}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            {session ? (
              <>
                <span className="hidden text-stone-500 sm:inline">{session.user.name}</span>
                <button type="button" className="btn btn-secondary !py-1.5" onClick={() => auth.signOut()}>Sign out</button>
              </>
            ) : (
              <Link to="/login" className="btn btn-secondary !py-1.5">Sign in</Link>
            )}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6"><Outlet /></main>
    </div>
  );
}
