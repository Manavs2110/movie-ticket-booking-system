package com.moviebooking.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The database clock. All seat-hold time comparisons use it, so several app instances
 * (or a skewed app clock) can never disagree about whether a hold has expired.
 * Inside a transaction Postgres' now() is the transaction start time.
 */
@Component
public class DbClock {

    private final JdbcTemplate jdbc;

    public DbClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        Timestamp ts = jdbc.queryForObject("SELECT now()", Timestamp.class);
        return ts.toInstant();
    }
}
