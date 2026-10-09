package io.github.anuragbhandary.ticketing.live;

/** One seat's new state. version only grows, so clients keep the change with the highest one. */
public record SeatChange(String label, String status, long version) {}
