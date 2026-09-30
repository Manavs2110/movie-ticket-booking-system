package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.config.AppProperties;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.ShowRequest;
import com.moviebooking.dto.catalog.ShowResponse;
import com.moviebooking.dto.catalog.ShowUpdateRequest;
import com.moviebooking.model.catalog.Movie;
import com.moviebooking.model.catalog.Screen;
import com.moviebooking.model.catalog.Show;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.catalog.ShowStatus;
import com.moviebooking.repository.catalog.ShowRepository;
import com.moviebooking.service.catalog.MovieService;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.catalog.ShowUsageChecker;
import com.moviebooking.service.catalog.TheaterService;
import jakarta.persistence.EntityManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** Show scheduling (LLD §7.4). The ex_show_no_overlap exclusion constraint backs up the pre-check. */
@Service
public class ShowServiceImpl implements ShowService {

    private static final String DETAILS_QUERY = """
            select new com.moviebooking.model.catalog.ShowDetails(
                s.id, m.id, m.title, sc.id, sc.name, sc.layoutVersion, t.id, t.name, t.cityId, t.refundPolicyId,
                s.startTime, s.endTime, s.regularPrice, s.premiumPrice, s.weekendMultiplier, s.status, s.version)
            from Show s, Movie m, Screen sc, Theater t
            where s.id = :id and m.id = s.movieId and sc.id = s.screenId and t.id = sc.theaterId
            """;

    private final ShowRepository showRepository;
    private final MovieService movieService;
    private final ScreenService screenService;
    private final TheaterService theaterService;
    private final ShowUsageChecker usageChecker;
    private final DbClock dbClock;
    private final EntityManager entityManager;
    private final Duration cleaningBuffer;
    private final BigDecimal defaultWeekendMultiplier;

    public ShowServiceImpl(ShowRepository showRepository, MovieService movieService, ScreenService screenService,
                       TheaterService theaterService, ShowUsageChecker usageChecker, DbClock dbClock,
                       EntityManager entityManager, AppProperties props) {
        this.showRepository = showRepository;
        this.movieService = movieService;
        this.screenService = screenService;
        this.theaterService = theaterService;
        this.usageChecker = usageChecker;
        this.dbClock = dbClock;
        this.entityManager = entityManager;
        this.cleaningBuffer = props.show().cleaningBuffer();
        this.defaultWeekendMultiplier = props.show().defaultWeekendMultiplier();
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SHOWS_FOR_MOVIE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MOVIES_IN_CITY, allEntries = true)})
    @Transactional
    public ShowResponse create(ShowRequest r) {
        Movie movie = movieService.entity(r.movieId());
        Screen screen = screenService.entity(r.screenId());
        if (!theaterService.entity(screen.getTheaterId()).isActive()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Theater is inactive");
        }
        Instant start = r.startTime().toInstant();
        requireFuture(start);
        Instant end = endTime(start, movie);
        requireNoOverlap(screen.getId(), start, end, 0L);
        Show show = showRepository.saveAndFlush(new Show(movie.getId(), screen.getId(), start, end, r.regularPrice(),
                r.premiumPrice(), r.weekendMultiplier() != null ? r.weekendMultiplier() : defaultWeekendMultiplier));
        return ShowResponse.from(details(show.getId()));
    }

    /** Time or price changes are only allowed while nobody has booked (or held) a seat. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SHOWS_FOR_MOVIE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MOVIES_IN_CITY, allEntries = true)})
    @Transactional
    public ShowResponse update(Long id, ShowUpdateRequest r) {
        Show show = showRepository.lockById(id).orElseThrow(() -> new AppException(ErrorCode.SHOW_NOT_FOUND,
                "Show " + id + " not found"));
        if (show.getStatus() != ShowStatus.SCHEDULED) {
            throw new AppException(ErrorCode.SHOW_NOT_BOOKABLE, "Show is cancelled");
        }
        if (usageChecker.hasBookings(id)) {
            throw new AppException(ErrorCode.CONFLICT, "Show already has bookings; time and price are frozen");
        }
        Instant start = r.startTime() != null ? r.startTime().toInstant() : show.getStartTime();
        BigDecimal regular = r.regularPrice() != null ? r.regularPrice() : show.getRegularPrice();
        BigDecimal premium = r.premiumPrice() != null ? r.premiumPrice() : show.getPremiumPrice();
        BigDecimal weekend = r.weekendMultiplier() != null ? r.weekendMultiplier() : show.getWeekendMultiplier();
        Instant end = show.getEndTime();
        if (!start.equals(show.getStartTime())) {
            requireFuture(start);
            end = endTime(start, movieService.entity(show.getMovieId()));
            requireNoOverlap(show.getScreenId(), start, end, show.getId());
        }
        show.reschedule(start, end, regular, premium, weekend);
        showRepository.flush();
        return ShowResponse.from(details(id));
    }

    /**
     * Marks the show cancelled. Returns false if it already was (so the admin action is idempotent).
     * Refunding its bookings is the booking module's job.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SHOWS_FOR_MOVIE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MOVIES_IN_CITY, allEntries = true)})
    @Transactional
    public boolean markCancelled(Long id) {
        Show show = showRepository.lockById(id).orElseThrow(() -> new AppException(ErrorCode.SHOW_NOT_FOUND,
                "Show " + id + " not found"));
        if (show.getStatus() == ShowStatus.CANCELLED) {
            return false;
        }
        show.cancel();
        return true;
    }

    /**
     * Takes a shared row lock on the show for the rest of the caller's transaction, so a concurrent
     * admin cancellation can't miss a booking that is being created right now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(Long id) {
        showRepository.lockSharedById(id).orElseThrow(() -> new AppException(ErrorCode.SHOW_NOT_FOUND,
                "Show " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public ShowResponse get(Long id) {
        return ShowResponse.from(details(id));
    }

    @Transactional(readOnly = true)
    public ShowDetails details(Long id) {
        return entityManager.createQuery(DETAILS_QUERY, ShowDetails.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.SHOW_NOT_FOUND, "Show " + id + " not found"));
    }

    private Instant endTime(Instant start, Movie movie) {
        return start.plus(Duration.ofMinutes(movie.getDurationMinutes())).plus(cleaningBuffer);
    }

    private void requireFuture(Instant start) {
        if (!start.isAfter(dbClock.now())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Show start time must be in the future");
        }
    }

    private void requireNoOverlap(Long screenId, Instant start, Instant end, Long excludeId) {
        if (showRepository.existsOverlap(screenId, start, end, excludeId)) {
            throw new AppException(ErrorCode.SHOW_OVERLAP, "Screen already has a show in that slot");
        }
    }
}
