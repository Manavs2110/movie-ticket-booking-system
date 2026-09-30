package com.moviebooking.service.booking;

import com.moviebooking.dto.booking.SeatMapResponse;

/**
 * Live seat map with ETag (LLD §9). A hot show gets thousands of polls per second, so the built map sits in a
 * 2-second Redis micro-cache: at most one Postgres rebuild per show every 2 s. The ETag is computed at build
 * time (never stored in Postgres): a lock expiring writes nothing, yet still changes the map within 2 s.
 * The map is only a display: the lock request is the final answer, so this staleness is safe.
 */
public interface SeatMapService {

    sealed interface Result permits Fresh, NotModified {
        String etag();
    }

    record Fresh(String etag, SeatMapResponse body) implements Result {
    }

    record NotModified(String etag) implements Result {
    }

    /** Redis key of a show's 2-second micro-cache. */
    static String cacheKey(Long showId) {
        return "seatmap:" + showId;
    }

    Result seatMap(Long showId, String ifNoneMatch);

    /** After a lock, confirm or cancel on this show, so the acting user sees the change immediately. */
    void evict(Long showId);
}
