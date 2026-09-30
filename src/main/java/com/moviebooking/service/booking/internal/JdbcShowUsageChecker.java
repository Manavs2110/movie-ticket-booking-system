package com.moviebooking.service.booking.internal;

import com.moviebooking.common.SqlArrays;
import com.moviebooking.service.catalog.ShowUsageChecker;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.Collection;

/** Booking-side answer to the catalog's "is this show / screen in use?" questions. */
@Component
class JdbcShowUsageChecker implements ShowUsageChecker {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcShowUsageChecker(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasBookings(Long showId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM booking WHERE show_id = :id)",
                new MapSqlParameterSource("id", showId), Boolean.class));
    }

    @Override
    public boolean hasSeatActivity(Collection<Long> showIds) {
        if (showIds.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM seat_lock
                               WHERE show_id = ANY(:ids) AND (booked OR held_until > now()))
                """, new MapSqlParameterSource().addValue("ids", SqlArrays.bigintArray(showIds), Types.ARRAY),
                Boolean.class));
    }
}
