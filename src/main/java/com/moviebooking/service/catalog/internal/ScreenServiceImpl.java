package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.AddSeatRowsRequest;
import com.moviebooking.dto.catalog.ScreenRequest;
import com.moviebooking.dto.catalog.ScreenResponse;
import com.moviebooking.dto.catalog.SeatResponse;
import com.moviebooking.dto.catalog.SeatRowRequest;
import com.moviebooking.dto.catalog.SeatUpdateRequest;
import com.moviebooking.model.catalog.Screen;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatLayout;
import com.moviebooking.repository.catalog.ScreenRepository;
import com.moviebooking.repository.catalog.SeatRepository;
import com.moviebooking.repository.catalog.ShowRepository;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowUsageChecker;
import com.moviebooking.service.catalog.TheaterService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Screens and seat layouts (LLD §7.5).
 * Seat type / active changes are allowed any time and bump the layout version (new cache key, new ETag).
 * Adding seats is blocked while future shows on the screen have held or sold seats.
 */
@Service
public class ScreenServiceImpl implements ScreenService {

    private final ScreenRepository screenRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final TheaterService theaterService;
    private final ShowUsageChecker usageChecker;
    private final DbClock dbClock;

    public ScreenServiceImpl(ScreenRepository screenRepository, SeatRepository seatRepository,
                         ShowRepository showRepository, TheaterService theaterService,
                         ShowUsageChecker usageChecker, DbClock dbClock) {
        this.screenRepository = screenRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.theaterService = theaterService;
        this.usageChecker = usageChecker;
        this.dbClock = dbClock;
    }

    @Transactional
    public ScreenResponse create(Long theaterId, ScreenRequest request) {
        theaterService.entity(theaterId);
        if (screenRepository.existsByTheaterIdAndNameIgnoreCase(theaterId, request.name().trim())) {
            throw new AppException(ErrorCode.CONFLICT, "Screen name already used in this theater");
        }
        requireDistinctRows(request.rows());
        Screen screen = screenRepository.save(new Screen(theaterId, request.name().trim()));
        List<Seat> seats = seatRepository.saveAll(buildSeats(screen.getId(), request.rows()));
        return ScreenResponse.from(screen, seats.size());
    }

    @Transactional(readOnly = true)
    public List<ScreenResponse> listByTheater(Long theaterId) {
        theaterService.entity(theaterId);
        return screenRepository.findByTheaterIdOrderByNameAsc(theaterId).stream()
                .map(s -> ScreenResponse.from(s, seatRepository.countByScreenId(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> seats(Long screenId) {
        entity(screenId);
        return seatRepository.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId).stream()
                .map(SeatResponse::from).toList();
    }

    @Transactional
    public List<SeatResponse> addRows(Long screenId, AddSeatRowsRequest request) {
        Screen screen = screenRepository.lockById(screenId).orElseThrow(() -> AppException.notFound("Screen", screenId));
        requireDistinctRows(request.rows());
        for (SeatRowRequest row : request.rows()) {
            if (seatRepository.existsByScreenIdAndRowLabel(screenId, row.rowLabel())) {
                throw new AppException(ErrorCode.CONFLICT, "Row " + row.rowLabel() + " already exists");
            }
        }
        List<Long> futureShows = showRepository.findFutureScheduledIds(screenId, dbClock.now());
        if (usageChecker.hasSeatActivity(futureShows)) {
            throw new AppException(ErrorCode.LAYOUT_IN_USE,
                    "Screen has upcoming shows with held or sold seats; layout size can't change now");
        }
        List<Seat> created = seatRepository.saveAll(buildSeats(screenId, request.rows()));
        screen.bumpLayoutVersion();
        return created.stream().map(SeatResponse::from).toList();
    }

    /** Type and active flag may change any time; existing bookings keep their snapshot price. */
    @Transactional
    public SeatResponse updateSeat(Long screenId, Long seatId, SeatUpdateRequest request) {
        Screen screen = screenRepository.lockById(screenId).orElseThrow(() -> AppException.notFound("Screen", screenId));
        Seat seat = seatRepository.findById(seatId)
                .filter(s -> s.getScreenId().equals(screenId))
                .orElseThrow(() -> AppException.notFound("Seat", seatId));
        seat.update(request.seatType() != null ? request.seatType() : seat.getSeatType(),
                request.active() != null ? request.active() : seat.isActive());
        screen.bumpLayoutVersion();
        return SeatResponse.from(seat);
    }

    /** Layout at a specific version; a layout change means a new version and therefore a new cache key. */
    @Cacheable(cacheNames = CacheNames.SEAT_LAYOUT, key = "#screenId + ':' + #layoutVersion")
    @Transactional(readOnly = true)
    public SeatLayout layout(Long screenId, int layoutVersion) {
        List<SeatLayout.LayoutSeat> seats = seatRepository.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId)
                .stream()
                .map(s -> new SeatLayout.LayoutSeat(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.label(),
                        s.getSeatType(), s.isActive()))
                .toList();
        return new SeatLayout(screenId, layoutVersion, seats);
    }

    /** Active seats of the screen among the given ids (for hold validation; never cached). */
    @Transactional(readOnly = true)
    public List<Seat> activeSeats(Long screenId, Collection<Long> seatIds) {
        return seatRepository.findByScreenIdAndIdIn(screenId, seatIds).stream().filter(Seat::isActive).toList();
    }

    @Transactional(readOnly = true)
    public Screen entity(Long id) {
        return screenRepository.findById(id).orElseThrow(() -> AppException.notFound("Screen", id));
    }

    private static List<Seat> buildSeats(Long screenId, List<SeatRowRequest> rows) {
        List<Seat> seats = new ArrayList<>();
        for (SeatRowRequest row : rows) {
            for (int n = 1; n <= row.seatCount(); n++) {
                seats.add(new Seat(screenId, row.rowLabel(), n, row.seatType()));
            }
        }
        return seats;
    }

    private static void requireDistinctRows(List<SeatRowRequest> rows) {
        Set<String> labels = new HashSet<>();
        for (SeatRowRequest row : rows) {
            if (!labels.add(row.rowLabel())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Duplicate row label " + row.rowLabel());
            }
        }
    }
}
