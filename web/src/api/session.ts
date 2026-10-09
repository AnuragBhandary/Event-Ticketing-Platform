// The signed-in user's JWT, kept in localStorage so a reload keeps you signed in.
//
// localStorage is readable by any script on the page, so an XSS bug could steal the token; the
// token is short-lived (8 h) and the site loads no third-party scripts. An HttpOnly cookie would
// remove that risk at the price of CSRF protection; see docs/DESIGN.md.

import type { Session } from "./types";

const KEY = "showtime.session";
const listeners = new Set<() => void>();

function load(): Session | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return Date.parse(session.expiresAt) > Date.now() ? session : null;
  } catch {
    return null;
  }
}

let current = load();

export const auth = {
  session: () => current,
  signIn(session: Session) {
    current = session;
    try {
      localStorage.setItem(KEY, JSON.stringify(session));
    } catch {
      // storage blocked: stay signed in for this page only
    }
    listeners.forEach((l) => l());
  },
  signOut() {
    current = null;
    try {
      localStorage.removeItem(KEY);
    } catch {
      // ignore
    }
    listeners.forEach((l) => l());
  },
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
