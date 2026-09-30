package com.moviebooking.model.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A physical seat in a screen's layout. Per-show state lives in seat_lock (booking module). */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "row_label", nullable = false, length = 3)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    /** false = broken / removed: kept for booking history, rejected for new holds. */
    @Column(nullable = false)
    private boolean active = true;

    protected Seat() {
    }

    public Seat(Long screenId, String rowLabel, int seatNumber, SeatType seatType) {
        this.screenId = screenId;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
    }

    public String label() {
        return rowLabel + seatNumber;
    }

    public void update(SeatType seatType, boolean active) {
        this.seatType = seatType;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public Long getScreenId() {
        return screenId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    public boolean isActive() {
        return active;
    }
}
