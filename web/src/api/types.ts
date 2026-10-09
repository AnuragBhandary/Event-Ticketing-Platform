// Response shapes of the ticketing API (api/src/main/java/.../ticketing).

export interface User {
  id: number;
  email: string;
  name: string;
  role: "USER" | "ADMIN";
}

export interface Session {
  token: string;
  expiresAt: string;
  user: User;
}

export interface EventSummary {
  id: number;
  title: string;
  venue: string;
  city: string;
  description: string;
  startsAt: string;
  rows: number;
  seatsPerRow: number;
  available: number;
  held: number;
  booked: number;
  minPricePaise: number;
  maxPricePaise: number;
}

export type SeatStatus = "AVAILABLE" | "HELD" | "BOOKED";

export interface Seat {
  label: string;
  row: string;
  number: number;
  pricePaise: number;
  status: SeatStatus;
  version: number;
}

export type HoldStatus = "ACTIVE" | "CONFIRMED" | "RELEASED" | "EXPIRED";
export type OrderStatus = "CREATED" | "PAID" | "FAILED" | "REFUNDED";

export interface Hold {
  id: string;
  eventId: number;
  seats: string[];
  amountPaise: number;
  status: HoldStatus;
  expiresAt: string;
  orderId: string | null;
  orderStatus: OrderStatus | null;
  bookingId: string | null;
}

export interface Checkout {
  orderId: string;
  amountPaise: number;
  status: OrderStatus;
  holdExpiresAt: string;
  paymentUrl: string;
}

export interface Booking {
  id: string;
  eventId: number;
  eventTitle: string;
  venue: string;
  city: string;
  startsAt: string;
  seats: string[];
  amountPaise: number;
  paymentId: string;
  createdAt: string;
}

export interface GatewayOrder {
  orderId: string;
  amountPaise: number;
  status: OrderStatus;
  merchant: string;
}

export interface PriceTier {
  fromRow: string;
  toRow: string;
  pricePaise: number;
}

export interface NewEvent {
  title: string;
  venue: string;
  city: string;
  description: string;
  startsAt: string;
  rows: number;
  seatsPerRow: number;
  tiers: PriceTier[];
}
