import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, RouterProvider, type RouteObject } from "react-router";

import { Layout } from "./components/Layout";
import { RequireAuth } from "./components/RequireAuth";
import { BookingsPage } from "./pages/BookingsPage";
import { EventPage } from "./pages/EventPage";
import { EventsPage } from "./pages/EventsPage";
import { HoldStatusPage } from "./pages/HoldStatusPage";
import { LoginPage } from "./pages/LoginPage";
import { NewEventPage } from "./pages/NewEventPage";
import { PayPage } from "./pages/PayPage";

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { path: "/", element: <EventsPage /> },
      { path: "/events/:eventId", element: <EventPage /> },
      { path: "/login", element: <LoginPage /> },
      { path: "/pay/:orderId", element: <PayPage /> },
      {
        element: <RequireAuth />,
        children: [
          { path: "/bookings", element: <BookingsPage /> },
          { path: "/holds/:holdId", element: <HoldStatusPage /> },
        ],
      },
      { element: <RequireAuth admin />, children: [{ path: "/admin/new", element: <NewEventPage /> }] },
    ],
  },
];

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });
const router = createBrowserRouter(routes);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
