package com.moviebooking.controller.catalog;

import com.moviebooking.dto.catalog.ShowRequest;
import com.moviebooking.dto.catalog.ShowResponse;
import com.moviebooking.dto.catalog.ShowUpdateRequest;
import com.moviebooking.service.catalog.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Scheduling. Cancelling a show lives in the booking module (it refunds bookings). */
@RestController
@RequestMapping("/api/admin/shows")
public class AdminShowController {

    private final ShowService showService;

    public AdminShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody ShowRequest request) {
        return showService.create(request);
    }

    @PutMapping("/{id}")
    public ShowResponse update(@PathVariable Long id, @Valid @RequestBody ShowUpdateRequest request) {
        return showService.update(id, request);
    }
}
