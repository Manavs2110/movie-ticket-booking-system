package com.moviebooking.model.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "show")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** start + movie duration + cleaning buffer; used by the overlap constraint. */
    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    /*
     * All pricing lives on the show:
     * seat price = (PREMIUM ? premiumPrice : regularPrice) × (starts Sat/Sun in IST ? weekendMultiplier : 1)
     */
    @Column(name = "regular_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal regularPrice;

    @Column(name = "premium_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal premiumPrice;

    @Column(name = "weekend_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal weekendMultiplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowStatus status = ShowStatus.SCHEDULED;

    /** +1 on any admin edit; part of the seat-map ETag. Not a JPA @Version: it's a content version. */
    @Column(nullable = false)
    private int version;

    protected Show() {
    }

    public Show(Long movieId, Long screenId, Instant startTime, Instant endTime, BigDecimal regularPrice,
                BigDecimal premiumPrice, BigDecimal weekendMultiplier) {
        this.movieId = movieId;
        this.screenId = screenId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.regularPrice = regularPrice;
        this.premiumPrice = premiumPrice;
        this.weekendMultiplier = weekendMultiplier;
    }

    public void reschedule(Instant startTime, Instant endTime, BigDecimal regularPrice, BigDecimal premiumPrice,
                           BigDecimal weekendMultiplier) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.regularPrice = regularPrice;
        this.premiumPrice = premiumPrice;
        this.weekendMultiplier = weekendMultiplier;
        this.version++;
    }

    public void cancel() {
        this.status = ShowStatus.CANCELLED;
        this.version++;
    }

    public Long getId() {
        return id;
    }

    public Long getMovieId() {
        return movieId;
    }

    public Long getScreenId() {
        return screenId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public BigDecimal getRegularPrice() {
        return regularPrice;
    }

    public BigDecimal getPremiumPrice() {
        return premiumPrice;
    }

    public BigDecimal getWeekendMultiplier() {
        return weekendMultiplier;
    }

    public ShowStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }
}
