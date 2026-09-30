package com.moviebooking.controller.catalog;

import com.moviebooking.common.PageResponse;
import com.moviebooking.config.AppProperties;
import com.moviebooking.dto.catalog.CityResponse;
import com.moviebooking.dto.catalog.MovieResponse;
import com.moviebooking.dto.catalog.MovieSummary;
import com.moviebooking.dto.catalog.ShowResponse;
import com.moviebooking.dto.catalog.TheaterResponse;
import com.moviebooking.dto.catalog.TheaterShowtimes;
import com.moviebooking.service.catalog.BrowseService;
import com.moviebooking.service.catalog.CityService;
import com.moviebooking.service.catalog.MovieService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.catalog.TheaterService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Public catalog. Responses carry {@code Cache-Control: public} so a CDN can sit in front later (HLD §10.4).
 */
@RestController
@RequestMapping("/api")
public class BrowseController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();
    private static final CacheControl LONG = CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

    private final CityService cityService;
    private final TheaterService theaterService;
    private final MovieService movieService;
    private final ShowService showService;
    private final BrowseService browseService;
    private final ZoneId zone;

    public BrowseController(CityService cityService, TheaterService theaterService, MovieService movieService,
                            ShowService showService, BrowseService browseService, AppProperties props) {
        this.cityService = cityService;
        this.theaterService = theaterService;
        this.movieService = movieService;
        this.showService = showService;
        this.browseService = browseService;
        this.zone = props.zoneId();
    }

    @GetMapping("/cities")
    public ResponseEntity<List<CityResponse>> cities() {
        return ResponseEntity.ok().cacheControl(LONG).body(cityService.list());
    }

    @GetMapping("/cities/{cityId}/theaters")
    public ResponseEntity<List<TheaterResponse>> theaters(@PathVariable Long cityId) {
        return ResponseEntity.ok().cacheControl(LONG).body(theaterService.listByCity(cityId));
    }

    @GetMapping("/cities/{cityId}/movies")
    public ResponseEntity<PageResponse<MovieSummary>> moviesInCity(
            @PathVariable Long cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        LocalDate day = date != null ? date : LocalDate.now(zone);
        return ResponseEntity.ok().cacheControl(SHORT)
                .body(browseService.moviesInCity(cityId, day, language, genre, page, size));
    }

    @GetMapping("/movies/{movieId}")
    public ResponseEntity<MovieResponse> movie(@PathVariable Long movieId) {
        return ResponseEntity.ok().cacheControl(LONG).body(movieService.get(movieId));
    }

    @GetMapping("/movies/{movieId}/shows")
    public ResponseEntity<PageResponse<TheaterShowtimes>> showsForMovie(
            @PathVariable Long movieId,
            @RequestParam Long cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(required = false) @DecimalMin("0.5") @DecimalMax("200") Double radiusKm,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        LocalDate day = date != null ? date : LocalDate.now(zone);
        boolean geo = lat != null && lng != null;
        return ResponseEntity.ok().cacheControl(geo ? CacheControl.noStore() : SHORT)
                .body(browseService.showsForMovie(movieId, cityId, day, geo ? lat : null, geo ? lng : null,
                        radiusKm, page, size));
    }

    @GetMapping("/shows/{showId}")
    public ResponseEntity<ShowResponse> show(@PathVariable Long showId) {
        return ResponseEntity.ok().cacheControl(SHORT).body(showService.get(showId));
    }
}
