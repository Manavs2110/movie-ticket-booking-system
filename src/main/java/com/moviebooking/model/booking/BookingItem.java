package com.moviebooking.model.booking;

import com.moviebooking.model.catalog.SeatType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Immutable price snapshot of one seat, taken at hold time. Admin price changes never touch it. */
@Entity
@Table(name = "booking_item")
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    protected BookingItem() {
    }

    public BookingItem(Long bookingId, Long seatId, String seatLabel, SeatType seatType, BigDecimal price) {
        this.bookingId = bookingId;
        this.seatId = seatId;
        this.seatLabel = seatLabel;
        this.seatType = seatType;
        this.price = price;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
