package com.moviebooking.service.booking.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.CursorCodec;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.common.PageResponse;
import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.RefundResponse;
import com.moviebooking.model.payment.RefundStatus;
import com.moviebooking.model.booking.Booking;
import com.moviebooking.model.booking.BookingItem;
import com.moviebooking.model.booking.BookingStatus;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.catalog.ShowStatus;
import com.moviebooking.repository.booking.BookingItemRepository;
import com.moviebooking.repository.booking.BookingRepository;
import com.moviebooking.service.booking.BookingQueryService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.pricing.DiscountService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read side of bookings: single booking, history (cursor pagination), admin listing. */
@Service
public class BookingQueryServiceImpl implements BookingQueryService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository itemRepository;
    private final ShowService showService;
    private final DiscountService discountService;
    private final DbClock dbClock;

    public BookingQueryServiceImpl(BookingRepository bookingRepository, BookingItemRepository itemRepository,
                               ShowService showService,
                               DiscountService discountService, DbClock dbClock) {
        this.bookingRepository = bookingRepository;
        this.itemRepository = itemRepository;
        this.showService = showService;
        this.discountService = discountService;
        this.dbClock = dbClock;
    }

    /** Someone else's booking is reported as not found, so booking ids can't be probed. */
    @Transactional(readOnly = true)
    public BookingResponse get(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> notFound(bookingId));
        return toResponse(booking, showService.details(booking.getShowId()),
                itemRepository.findByBookingIdOrderBySeatIdAsc(bookingId), dbClock.now());
    }

    @Transactional(readOnly = true)
    public BookingResponse getAsAdmin(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> notFound(bookingId));
        return toResponse(booking, showService.details(booking.getShowId()),
                itemRepository.findByBookingIdOrderBySeatIdAsc(bookingId), dbClock.now());
    }

    /** Newest first, keyset on (created_at, id): stays fast however deep the user pages. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> history(Long userId, String cursor, int size) {
        PageRequest limit = PageRequest.of(0, size + 1);
        List<Booking> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = bookingRepository.firstHistoryPage(userId, limit);
        } else {
            CursorCodec.Cursor c = CursorCodec.decode(cursor);
            rows = bookingRepository.historyPageAfter(userId, c.createdAt(), c.id(), limit);
        }
        boolean hasNext = rows.size() > size;
        List<Booking> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext
                ? CursorCodec.encode(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;
        return PageResponse.cursor(toResponses(page), size, next);
    }

    /** Used by the notification consumer before sending a reminder that may have been queued earlier. */
    @Transactional(readOnly = true)
    public boolean isStillConfirmedAndScheduled(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .map(b -> showService.details(b.getShowId()).status() == ShowStatus.SCHEDULED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> forShow(Long showId) {
        showService.details(showId);
        return toResponses(bookingRepository.findByShowIdOrderByIdAsc(showId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> refunds(RefundStatus status) {
        List<Booking> bookings = status == null
                ? bookingRepository.findByRefundStatusNotNullOrderByIdDesc()
                : bookingRepository.findByRefundStatusOrderByIdDesc(status);
        return bookings.stream().map(RefundResponse::from).toList();
    }

    private List<BookingResponse> toResponses(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Instant now = dbClock.now();
        Map<Long, List<BookingItem>> itemsByBooking = itemRepository
                .findByBookingIdIn(bookings.stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.groupingBy(BookingItem::getBookingId));
        Map<Long, ShowDetails> shows = new HashMap<>();
        return bookings.stream()
                .map(b -> toResponse(b, shows.computeIfAbsent(b.getShowId(), showService::details),
                        itemsByBooking.getOrDefault(b.getId(), List.of()), now))
                .toList();
    }

    private BookingResponse toResponse(Booking b, ShowDetails show, List<BookingItem> items, Instant now) {
        BookingStatus status = b.effectiveStatus(now);
        boolean holdActive = status == BookingStatus.HELD || status == BookingStatus.PAYMENT_PENDING;
        BookingResponse.RefundInfo refund = b.hasRefund()
                ? new BookingResponse.RefundInfo(b.getRefundAmount(), b.getRefundPercent(), b.getRefundReason(),
                        b.getRefundStatus())
                : null;
        List<BookingResponse.Item> itemViews = items.stream()
                .sorted((x, y) -> x.getSeatId().compareTo(y.getSeatId()))
                .map(i -> new BookingResponse.Item(i.getSeatId(), i.getSeatLabel(), i.getSeatType(), i.getPrice()))
                .toList();
        return new BookingResponse(b.getId(), status, show.id(), show.movieTitle(), show.theaterName(),
                show.screenName(), show.startTime(), holdActive ? b.getHoldExpiresAt() : null, itemViews,
                b.getSubtotal(), b.getDiscountCodeId() == null ? null : discountService.codeOf(b.getDiscountCodeId()),
                b.getDiscountAmount(), b.getTotalAmount(), refund, b.getCreatedAt());
    }

    private static AppException notFound(Long bookingId) {
        return new AppException(ErrorCode.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found");
    }
}
