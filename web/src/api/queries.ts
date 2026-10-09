import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { http } from "./http";
import type { Booking, Checkout, EventSummary, GatewayOrder, Hold, NewEvent, Seat, Session } from "./types";

export const useEvents = () =>
  useQuery({ queryKey: ["events"], queryFn: () => http.get<EventSummary[]>("/api/events"), refetchInterval: 15_000 });

export const useEvent = (id: number) =>
  useQuery({ queryKey: ["event", id], queryFn: () => http.get<EventSummary>(`/api/events/${id}`), retry: false });

export const useSeats = (id: number) =>
  useQuery({ queryKey: ["seats", id], queryFn: () => http.get<Seat[]>(`/api/events/${id}/seats`), staleTime: Infinity });

/** A hold's status, polled while the payment is being confirmed. */
export const useHold = (id: string, poll: boolean) =>
  useQuery({
    queryKey: ["hold", id],
    queryFn: () => http.get<Hold>(`/api/holds/${id}`),
    refetchInterval: (q) => (poll && q.state.data?.orderStatus !== "PAID" && q.state.data?.orderStatus !== "REFUNDED" ? 1_000 : false),
  });

export const useBookings = () => useQuery({ queryKey: ["bookings"], queryFn: () => http.get<Booking[]>("/api/bookings") });

export const useGatewayOrder = (orderId: string) =>
  useQuery({ queryKey: ["gateway", orderId], queryFn: () => http.get<GatewayOrder>(`/api/fake-gateway/orders/${orderId}`) });

export const login = (email: string, password: string) => http.post<Session>("/api/auth/login", { email, password });
export const register = (name: string, email: string, password: string) =>
  http.post<Session>("/api/auth/register", { name, email, password });

export function useHoldSeats() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, seats }: { eventId: number; seats: string[] }) => http.post<Hold>("/api/holds", { eventId, seats }),
    onSuccess: (hold) => client.setQueryData(["hold", hold.id], hold),
  });
}

export const useCancelHold = () => useMutation({ mutationFn: (id: string) => http.del(`/api/holds/${id}`) });

export const useCheckout = () =>
  useMutation({ mutationFn: (holdId: string) => http.post<Checkout>(`/api/holds/${holdId}/checkout`) });

export const usePay = () =>
  useMutation({
    mutationFn: ({ orderId, outcome }: { orderId: string; outcome: "success" | "failure" }) =>
      http.post<{ paymentId: string; deliveries: string[] }>(`/api/fake-gateway/orders/${orderId}/pay`, { outcome }),
  });

export function useCreateEvent() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (event: NewEvent) => http.post<EventSummary>("/api/events", event),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["events"] }),
  });
}
