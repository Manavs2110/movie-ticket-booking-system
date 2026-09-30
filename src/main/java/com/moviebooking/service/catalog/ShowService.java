package com.moviebooking.service.catalog;

import com.moviebooking.dto.catalog.ShowRequest;
import com.moviebooking.dto.catalog.ShowResponse;
import com.moviebooking.dto.catalog.ShowUpdateRequest;
import com.moviebooking.model.catalog.ShowDetails;

/** Show scheduling (LLD §7.4). The ex_show_no_overlap exclusion constraint backs up the pre-check. */
public interface ShowService {

    ShowResponse create(ShowRequest r);

    ShowResponse update(Long id, ShowUpdateRequest r);

    boolean markCancelled(Long id);

    /**
     * Takes a shared row lock on the show for the rest of the caller's transaction, so a concurrent
     * admin cancellation can't miss a booking that is being created right now.
     */
    void lockForBooking(Long id);

    ShowResponse get(Long id);

    ShowDetails details(Long id);
}
