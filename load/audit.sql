-- After a rush: was any seat sold twice, or lost? Run against the most recent event.
\set ON_ERROR_STOP on
WITH e AS (SELECT max(id) AS id FROM events),
     booked_seats AS (SELECT count(*) AS n FROM seats, e WHERE seats.event_id = e.id AND status = 'BOOKED'),
     booking_seats AS (SELECT b.id, unnest(b.seat_labels) AS label FROM bookings b, e WHERE b.event_id = e.id),
     doubles AS (SELECT label FROM booking_seats GROUP BY label HAVING count(*) > 1),
     mismatched AS (SELECT s.label FROM seats s JOIN e ON s.event_id = e.id
                    LEFT JOIN booking_seats bs ON bs.label = s.label AND bs.id = s.booking_id
                    WHERE s.status = 'BOOKED' AND bs.id IS NULL),
     paid AS (SELECT count(*) AS n FROM payment_orders o JOIN holds h ON h.id = o.hold_id, e WHERE h.event_id = e.id AND o.status = 'PAID'),
     bookings_n AS (SELECT count(*) AS n FROM bookings b, e WHERE b.event_id = e.id)
SELECT (SELECT n FROM booked_seats)                       AS seats_booked,
       (SELECT count(*) FROM booking_seats)                AS seats_in_bookings,
       (SELECT count(*) FROM doubles)                      AS seats_sold_twice,
       (SELECT count(*) FROM mismatched)                   AS booked_seats_without_matching_booking,
       (SELECT n FROM bookings_n)                          AS bookings,
       (SELECT n FROM paid)                                AS paid_orders;
