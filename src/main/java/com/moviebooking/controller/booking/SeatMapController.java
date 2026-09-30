package com.moviebooking.controller.booking;

import com.moviebooking.dto.booking.SeatMapResponse;
import com.moviebooking.service.booking.SeatMapService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Poll with If-None-Match; 304 (no body) when nothing changed. Never cached by intermediaries. */
@RestController
public class SeatMapController {

    private final SeatMapService seatMapService;

    public SeatMapController(SeatMapService seatMapService) {
        this.seatMapService = seatMapService;
    }

    @GetMapping("/api/shows/{showId}/seats")
    public ResponseEntity<SeatMapResponse> seatMap(@PathVariable Long showId,
                                                   @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                                   String ifNoneMatch) {
        SeatMapService.Result result = seatMapService.seatMap(showId, ifNoneMatch);
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result instanceof SeatMapService.NotModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(result.etag())
                .cacheControl(CacheControl.noStore());
        return result instanceof SeatMapService.Fresh fresh ? builder.body(fresh.body()) : builder.build();
    }
}
