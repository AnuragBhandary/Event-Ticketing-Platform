-- Event ticketing schema. Seats are rows of their own: a seat's status, its hold and its booking
-- live on one row, so "this seat is booked by exactly one booking" is a property of the schema,
-- not of application code. Money is in paise (integer), never floating point.

CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          TEXT        NOT NULL UNIQUE,            -- stored lower-cased
    name           TEXT        NOT NULL,
    password_hash  TEXT        NOT NULL,                   -- bcrypt, via Spring Security
    role           TEXT        NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id             BIGSERIAL PRIMARY KEY,
    title          TEXT        NOT NULL,
    venue          TEXT        NOT NULL,
    city           TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    starts_at      TIMESTAMPTZ NOT NULL,
    seat_rows      INTEGER     NOT NULL CHECK (seat_rows BETWEEN 1 AND 26),
    seats_per_row  INTEGER     NOT NULL CHECK (seats_per_row BETWEEN 1 AND 40),
    created_by     BIGINT      NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX events_upcoming ON events (starts_at);

-- One row per seat per event. A HELD seat names its hold and when that hold lapses; a BOOKED
-- seat names its booking. version increases on every change, so clients that receive live updates
-- out of order keep the newest.
CREATE TABLE seats (
    event_id         BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    label            TEXT        NOT NULL,                 -- e.g. C12
    row_label        TEXT        NOT NULL,
    number           INTEGER     NOT NULL,
    price_paise      INTEGER     NOT NULL CHECK (price_paise > 0),
    status           TEXT        NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id          UUID,
    hold_expires_at  TIMESTAMPTZ,
    booking_id       UUID,
    version          BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, label),
    CONSTRAINT seat_hold_consistent
        CHECK ((status = 'HELD') = (hold_id IS NOT NULL AND hold_expires_at IS NOT NULL)),
    CONSTRAINT seat_booking_consistent CHECK ((status = 'BOOKED') = (booking_id IS NOT NULL))
);
CREATE INDEX seats_expiring ON seats (hold_expires_at) WHERE status = 'HELD';

-- A user's claim on some seats for a few minutes while they pay.
CREATE TABLE holds (
    id            UUID        PRIMARY KEY,
    event_id      BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    user_id       BIGINT      NOT NULL REFERENCES users (id),
    seat_labels   TEXT[]      NOT NULL,
    amount_paise  INTEGER     NOT NULL CHECK (amount_paise > 0),
    status        TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX holds_user ON holds (user_id, created_at DESC);
CREATE INDEX holds_active_expiry ON holds (expires_at) WHERE status = 'ACTIVE';

-- A payment order at the gateway, one per hold (checkout is idempotent per hold).
CREATE TABLE payment_orders (
    id            TEXT        PRIMARY KEY,                 -- gateway order id, e.g. order_...
    hold_id       UUID        NOT NULL UNIQUE REFERENCES holds (id),
    amount_paise  INTEGER     NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('CREATED', 'PAID', 'FAILED', 'REFUNDED')),
    payment_id    TEXT        UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id            UUID        PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id),
    event_id      BIGINT      NOT NULL REFERENCES events (id),
    hold_id       UUID        NOT NULL UNIQUE REFERENCES holds (id),
    order_id      TEXT        NOT NULL UNIQUE REFERENCES payment_orders (id),
    payment_id    TEXT        NOT NULL UNIQUE,
    seat_labels   TEXT[]      NOT NULL,
    amount_paise  INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bookings_user ON bookings (user_id, created_at DESC);

-- Gateway webhook deliveries already processed. Gateways deliver at least once, so the same
-- event can arrive twice (or concurrently); the primary key makes processing idempotent.
CREATE TABLE webhook_events (
    id           TEXT        PRIMARY KEY,
    type         TEXT        NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
