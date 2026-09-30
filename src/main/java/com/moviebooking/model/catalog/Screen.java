package com.moviebooking.model.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "screen")
public class Screen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "theater_id", nullable = false)
    private Long theaterId;

    @Column(nullable = false)
    private String name;

    /** +1 on every layout change; part of the seat-map ETag and the layout cache key. */
    @Column(name = "layout_version", nullable = false)
    private int layoutVersion = 1;

    protected Screen() {
    }

    public Screen(Long theaterId, String name) {
        this.theaterId = theaterId;
        this.name = name;
    }

    public void bumpLayoutVersion() {
        layoutVersion++;
    }

    public Long getId() {
        return id;
    }

    public Long getTheaterId() {
        return theaterId;
    }

    public String getName() {
        return name;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }
}
