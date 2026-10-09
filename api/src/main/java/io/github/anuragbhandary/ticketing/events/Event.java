package io.github.anuragbhandary.ticketing.events;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "seat_rows", nullable = false)
    private int seatRows;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    @Column(name = "created_by", nullable = false)
    private long createdBy;

    protected Event() {}

    public Event(String title, String venue, String city, String description, Instant startsAt,
                 int seatRows, int seatsPerRow, long createdBy) {
        this.title = title;
        this.venue = venue;
        this.city = city;
        this.description = description;
        this.startsAt = startsAt;
        this.seatRows = seatRows;
        this.seatsPerRow = seatsPerRow;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public String getCity() { return city; }
    public String getDescription() { return description; }
    public Instant getStartsAt() { return startsAt; }
    public int getSeatRows() { return seatRows; }
    public int getSeatsPerRow() { return seatsPerRow; }
}
