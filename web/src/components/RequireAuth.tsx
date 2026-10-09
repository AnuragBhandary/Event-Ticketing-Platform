import { useSyncExternalStore } from "react";
import { Navigate, Outlet, useLocation } from "react-router";

import { auth } from "../api/session";

/** Pages behind sign-in send you to /login and back again afterwards. */
export function RequireAuth({ admin = false }: { admin?: boolean }) {
  const session = useSyncExternalStore(auth.subscribe, auth.session);
  const location = useLocation();
  if (!session) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (admin && session.user.role !== "ADMIN") return <p role="alert">Only admins can create events.</p>;
  return <Outlet />;
}
