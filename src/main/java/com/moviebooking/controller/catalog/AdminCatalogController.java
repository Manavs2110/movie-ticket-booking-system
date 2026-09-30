package com.moviebooking.controller.catalog;

import com.moviebooking.dto.catalog.AddSeatRowsRequest;
import com.moviebooking.dto.catalog.CityRequest;
import com.moviebooking.dto.catalog.CityResponse;
import com.moviebooking.dto.catalog.MovieRequest;
import com.moviebooking.dto.catalog.MovieResponse;
import com.moviebooking.dto.catalog.ScreenRequest;
import com.moviebooking.dto.catalog.ScreenResponse;
import com.moviebooking.dto.catalog.SeatResponse;
import com.moviebooking.dto.catalog.SeatUpdateRequest;
import com.moviebooking.dto.catalog.TheaterRequest;
import com.moviebooking.dto.catalog.TheaterResponse;
import com.moviebooking.service.catalog.CityService;
import com.moviebooking.service.catalog.MovieService;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.TheaterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin entry point onto the same catalog services (cities, theaters, screens/layouts, movies). */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final CityService cityService;
    private final TheaterService theaterService;
    private final ScreenService screenService;
    private final MovieService movieService;

    public AdminCatalogController(CityService cityService, TheaterService theaterService,
                                  ScreenService screenService, MovieService movieService) {
        this.cityService = cityService;
        this.theaterService = theaterService;
        this.screenService = screenService;
        this.movieService = movieService;
    }

    // ---- cities ----
    @GetMapping("/cities")
    public List<CityResponse> cities() {
        return cityService.list();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public CityResponse createCity(@Valid @RequestBody CityRequest request) {
        return cityService.create(request);
    }

    @PutMapping("/cities/{id}")
    public CityResponse updateCity(@PathVariable Long id, @Valid @RequestBody CityRequest request) {
        return cityService.update(id, request);
    }

    // ---- theaters ----
    @GetMapping("/theaters")
    public List<TheaterResponse> theaters(@RequestParam Long cityId) {
        return theaterService.listByCity(cityId);
    }

    @GetMapping("/theaters/{id}")
    public TheaterResponse theater(@PathVariable Long id) {
        return theaterService.get(id);
    }

    @PostMapping("/theaters")
    @ResponseStatus(HttpStatus.CREATED)
    public TheaterResponse createTheater(@Valid @RequestBody TheaterRequest request) {
        return theaterService.create(request);
    }

    @PutMapping("/theaters/{id}")
    public TheaterResponse updateTheater(@PathVariable Long id, @Valid @RequestBody TheaterRequest request) {
        return theaterService.update(id, request);
    }

    // ---- screens and seat layouts ----
    @GetMapping("/theaters/{theaterId}/screens")
    public List<ScreenResponse> screens(@PathVariable Long theaterId) {
        return screenService.listByTheater(theaterId);
    }

    @PostMapping("/theaters/{theaterId}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenResponse createScreen(@PathVariable Long theaterId, @Valid @RequestBody ScreenRequest request) {
        return screenService.create(theaterId, request);
    }

    @GetMapping("/screens/{screenId}/seats")
    public List<SeatResponse> seats(@PathVariable Long screenId) {
        return screenService.seats(screenId);
    }

    @PostMapping("/screens/{screenId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    public List<SeatResponse> addRows(@PathVariable Long screenId, @Valid @RequestBody AddSeatRowsRequest request) {
        return screenService.addRows(screenId, request);
    }

    @PutMapping("/screens/{screenId}/seats/{seatId}")
    public SeatResponse updateSeat(@PathVariable Long screenId, @PathVariable Long seatId,
                                   @RequestBody SeatUpdateRequest request) {
        return screenService.updateSeat(screenId, seatId, request);
    }

    // ---- movies ----
    @GetMapping("/movies")
    public List<MovieResponse> movies() {
        return movieService.listAll();
    }

    @PostMapping("/movies")
    @ResponseStatus(HttpStatus.CREATED)
    public MovieResponse createMovie(@Valid @RequestBody MovieRequest request) {
        return movieService.create(request);
    }

    @PutMapping("/movies/{id}")
    public MovieResponse updateMovie(@PathVariable Long id, @Valid @RequestBody MovieRequest request) {
        return movieService.update(id, request);
    }
}
