package com.moviebooking.service.booking.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.common.SeatsUnavailableException;
import com.moviebooking.config.AppProperties;
import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.dto.booking.ShowCancellationResult;
import com.moviebooking.dto.pricing.PriceBreakdown;
import com.moviebooking.model.booking.Booking;
import com.moviebooking.model.booking.BookingItem;
import com.moviebooking.model.booking.BookingStatus;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatLayout;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.catalog.ShowStatus;
import com.moviebooking.model.notification.EventType;
import com.moviebooking.model.payment.Payment;
import com.moviebooking.model.payment.RefundReason;
import com.moviebooking.repository.booking.BookingItemRepository;
import com.moviebooking.repository.booking.BookingRepository;
import com.moviebooking.service.booking.BookingQueryService;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.service.booking.SeatMapService;
import com.moviebooking.service.booking.internal.seathold.SeatHoldService.Reservation;
import com.moviebooking.service.booking.internal.seathold.SeatHoldService;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.payment.PaymentGateway.ChargeResult;
import com.moviebooking.service.payment.PaymentGateway.RefundResult;
import com.moviebooking.service.payment.PaymentService;
import com.moviebooking.service.pricing.CheckoutPricing;
import com.moviebooking.service.pricing.DiscountService;
import com.moviebooking.service.refundpolicy.RefundPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Booking lifecycle: lock → pay → confirm, and cancellation (LLD §6).
 * <p>
 * Transactions are opened explicitly with {@link TransactionTemplate} so they stay short, and the payment
 * gateway is always called with <b>no</b> transaction open: row locks are never held across a network call.
 * Seat locking goes through {@link SeatHoldService}; this class doesn't know it has a Redis and a Postgres layer.
 */
@Service
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int FULL_REFUND = 100;

    private final BookingRepository bookingRepository;
    private final BookingItemRepository itemRepository;
    private final SeatHoldService seatHold;
    private final ShowService showService;
    private final ScreenService screenService;
    private final CheckoutPricing checkoutPricing;
    private final DiscountService discountService;
    private final RefundPolicyService refundPolicyService;
    private final PaymentService paymentService;
    private final BookingNotifications notifications;
    private final BookingQueryService queries;
    private final SeatMapService seatMap;
    private final DbClock dbClock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration holdDuration;
    private final Duration paymentWindow;
    private final Duration reminderLead;
    private final int maxSeats;

    public BookingServiceImpl(BookingRepository bookingRepository, BookingItemRepository itemRepository,
                          SeatHoldService seatHold, ShowService showService, ScreenService screenService,
                          CheckoutPricing checkoutPricing, DiscountService discountService,
                          RefundPolicyService refundPolicyService, PaymentService paymentService,
                          BookingNotifications notifications, BookingQueryService queries, SeatMapService seatMap,
                          DbClock dbClock, JdbcTemplate jdbc, TransactionTemplate tx, AppProperties props) {
        this.bookingRepository = bookingRepository;
        this.itemRepository = itemRepository;
        this.seatHold = seatHold;
        this.showService = showService;
        this.screenService = screenService;
        this.checkoutPricing = checkoutPricing;
        this.discountService = discountService;
        this.refundPolicyService = refundPolicyService;
        this.paymentService = paymentService;
        this.notifications = notifications;
        this.queries = queries;
        this.seatMap = seatMap;
        this.dbClock = dbClock;
        this.jdbc = jdbc;
        this.tx = tx;
        this.holdDuration = props.booking().holdDuration();
        this.paymentWindow = props.booking().paymentWindow();
        this.reminderLead = props.reminder().leadTime();
        this.maxSeats = props.booking().maxSeatsPerBooking();
    }

    // =====================================================================================
    // 6.1 Lock seats: all or nothing. Redis filter first, then the Postgres claim decides.
    // =====================================================================================

    public BookingResponse hold(Long userId, HoldRequest request) {
        List<Long> seatIds = request.seatIds();
        if (seatIds.size() > maxSeats) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "At most " + maxSeats + " seats per booking");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new AppException(ErrorCode.INVALID_SEATS, "Duplicate seat ids");
        }
        List<Long> sortedIds = seatIds.stream().sorted().toList();

        // One id for the Redis lock value and the booking row.
        Long bookingId = jdbc.queryForObject("SELECT nextval('booking_id_seq')", Long.class);

        Reservation reservation = seatHold.reserve(request.showId(), sortedIds, bookingId);
        if (reservation.rejected()) {
            throw new SeatsUnavailableException(reservation.taken());       // Postgres never touched
        }
        try {
            tx.executeWithoutResult(status -> claim(userId, bookingId, request, reservation));
        } catch (RuntimeException e) {
            seatHold.abandon(reservation);
            throw e;
        }
        seatMap.evict(request.showId());
        return queries.get(userId, bookingId);
    }

    private void claim(Long userId, Long bookingId, HoldRequest request, Reservation reservation) {
        Instant now = dbClock.now();
        showService.lockForBooking(request.showId());                 // FOR SHARE: admin cancel waits for us
        ShowDetails show = showService.details(request.showId());
        if (show.status() != ShowStatus.SCHEDULED || !show.startTime().isAfter(now)) {
            throw new AppException(ErrorCode.SHOW_NOT_BOOKABLE, "Show is cancelled or has already started");
        }
        List<Seat> seats = checkoutPricing.validSeats(show, request.seatIds());

        expireStaleHolds(userId, show.id(), now);
        if (bookingRepository.existsActiveHold(userId, show.id(), now)) {
            throw new AppException(ErrorCode.ACTIVE_HOLD_EXISTS,
                    "You already have an active hold for this show; pay for it or cancel it first");
        }

        PriceBreakdown price = checkoutPricing.price(show, seats, request.discountCode(), userId);
        Booking booking = bookingRepository.saveAndFlush(new Booking(bookingId, userId, show.id(), price.subtotal(),
                price.discount(), price.total(), price.discountCodeId(), now.plus(holdDuration)));
        itemRepository.saveAll(price.items().stream()
                .map(p -> new BookingItem(booking.getId(), p.seatId(), p.label(), p.seatType(), p.price()))
                .toList());

        List<Long> claimed = seatHold.claim(reservation, holdDuration);
        if (claimed.size() != reservation.seatIds().size()) {
            Set<Long> missing = new HashSet<>(reservation.seatIds());
            claimed.forEach(missing::remove);
            throw new SeatsUnavailableException(missing);             // Postgres wins; everything rolls back
        }
    }

    /** A stale HELD row would block the one-active-hold index; save it as EXPIRED and free its seats. */
    private void expireStaleHolds(Long userId, Long showId, Instant now) {
        for (Booking stale : bookingRepository.lockStaleHolds(userId, showId, now)) {
            stale.expire();
            seatHold.release(stale.getId());
        }
        bookingRepository.flush();
    }

    // =====================================================================================
    // 6.2 Pay: prepare (TX1) → charge (no TX) → confirm (TX2) | decline | compensate (TX3)
    // =====================================================================================

    private sealed interface Prepared permits Proceed, HoldLost {
    }

    private record Proceed(Payment payment, int seatCount) implements Prepared {
    }

    private record HoldLost() implements Prepared {
    }

    private static final class ConfirmFailedException extends RuntimeException {
        ConfirmFailedException() {
            super("Seats could not be confirmed", null, false, false);
        }
    }

    public BookingResponse pay(Long userId, Long bookingId, String idempotencyKey, String paymentToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header (1-64 chars) is required");
        }
        Optional<BookingResponse> replay = replay(userId, bookingId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        Prepared prepared;
        try {
            prepared = tx.execute(status -> prepare(userId, bookingId, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request using the same key: that request owns the payment.
            return replay(userId, bookingId, idempotencyKey).orElseThrow(() -> e);
        }
        if (prepared instanceof HoldLost) {
            evictSeatMapOf(bookingId);
            throw new AppException(ErrorCode.HOLD_EXPIRED, "Your seat lock has expired; no payment was taken");
        }
        Proceed proceed = (Proceed) prepared;
        Payment payment = proceed.payment();

        ChargeResult charge = paymentService.charge(payment, paymentToken);   // no transaction open

        if (!charge.approved()) {
            tx.executeWithoutResult(status -> {
                paymentService.markFailed(payment.getId(), charge.failureReason());
                Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
                if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
                    booking.backToHeld();
                }
                giveDiscountBack(booking);
            });
            throw new AppException(ErrorCode.PAYMENT_FAILED,
                    "Payment declined: " + charge.failureReason() + ". You can retry while your seats are locked");
        }

        try {
            tx.executeWithoutResult(status -> confirm(bookingId, proceed.seatCount(), payment.getId(),
                    charge.gatewayRef()));
            evictSeatMapOf(bookingId);
            return queries.get(userId, bookingId);
        } catch (ConfirmFailedException e) {
            compensate(bookingId, payment, charge.gatewayRef());
            evictSeatMapOf(bookingId);
            throw new AppException(ErrorCode.HOLD_EXPIRED,
                    "Your seat lock expired before the booking could be confirmed; the payment has been refunded");
        }
    }

    /** Same key again: same booking → current state (no second charge); other booking → 409. */
    private Optional<BookingResponse> replay(Long userId, Long bookingId, String key) {
        return paymentService.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getBookingId().equals(bookingId)) {
                throw new AppException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different booking");
            }
            return queries.get(userId, bookingId);
        });
    }

    private Prepared prepare(Long userId, Long bookingId, String idempotencyKey) {
        Booking booking = bookingRepository.lockByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found"));
        Instant now = dbClock.now();
        switch (booking.getStatus()) {
            case HELD -> { }
            case PAYMENT_PENDING -> throw new AppException(ErrorCode.PAYMENT_IN_PROGRESS,
                    "A payment for this booking is already in progress");
            default -> throw new AppException(ErrorCode.INVALID_BOOKING_STATE,
                    "Booking is " + booking.effectiveStatus(now) + " and can't be paid");
        }
        if (booking.effectiveStatus(now) == BookingStatus.EXPIRED) {
            return expireAndRelease(booking);
        }
        ShowDetails show = showService.details(booking.getShowId());
        if (show.status() != ShowStatus.SCHEDULED) {
            // The admin cancel-show run releases and cancels this booking; just refuse to charge.
            throw new AppException(ErrorCode.SHOW_NOT_BOOKABLE, "Show was cancelled; no payment was taken");
        }
        int seatCount = itemRepository.countByBookingId(bookingId);
        Optional<Instant> lockedUntil = seatHold.extend(bookingId, seatCount, paymentWindow);
        if (lockedUntil.isEmpty()) {
            return expireAndRelease(booking);
        }
        booking.startPayment(lockedUntil.get());
        if (booking.getDiscountCodeId() != null) {
            discountService.redeem(booking.getDiscountCodeId(), userId);   // 422 → rollback, no charge
            booking.markDiscountRedeemed();
        }
        Payment payment = paymentService.startPayment(bookingId, booking.getTotalAmount(), idempotencyKey);
        return new Proceed(payment, seatCount);
    }

    private HoldLost expireAndRelease(Booking booking) {
        booking.expire();
        seatHold.release(booking.getId());
        return new HoldLost();
    }

    private void confirm(Long bookingId, int seatCount, Long paymentId, String gatewayRef) {
        Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            throw new ConfirmFailedException();      // e.g. admin cancelled the show meanwhile
        }
        ShowDetails show = showService.details(booking.getShowId());
        if (!seatHold.confirm(bookingId, seatCount, show.endTime())) {
            throw new ConfirmFailedException();
        }
        booking.confirm();
        paymentService.markSucceeded(paymentId, gatewayRef);
        notifications.publish(EventType.BOOKING_CONFIRMED, booking, show);
        Instant reminderDue = show.startTime().minus(reminderLead);
        if (reminderDue.isAfter(dbClock.now())) {
            // A booking made inside the lead time gets no reminder: the confirmation is enough.
            notifications.scheduleReminder(booking, show, reminderDue);
        }
    }

    /** Money was taken but the seats couldn't be confirmed: refund in full, give the discount back. */
    private void compensate(Long bookingId, Payment payment, String gatewayRef) {
        log.warn("Confirm failed for booking {} after a successful charge; refunding {}", bookingId, gatewayRef);
        RefundResult result = paymentService.refundAtGateway(gatewayRef, payment.getAmount());
        tx.executeWithoutResult(status -> {
            paymentService.markSucceeded(payment.getId(), gatewayRef);
            Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
            booking.startRefund(payment.getAmount(), FULL_REFUND, RefundReason.CONFIRM_FAILED);
            booking.completeRefund(result.success(), result.gatewayRef());
            if (result.success()) {
                paymentService.markRefunded(payment.getId(), true);
            }
            if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
                booking.expire();
            }
            giveDiscountBack(booking);
            seatHold.release(bookingId);
            if (result.success()) {
                notifications.publish(EventType.REFUND_PROCESSED, booking, showService.details(booking.getShowId()),
                        BookingNotifications.refundExtra(payment.getAmount(), FULL_REFUND));
            }
        });
    }

    /** Undo the code's use counted at payment (declined card, failed confirm). */
    private void giveDiscountBack(Booking booking) {
        if (booking.isDiscountRedeemed()) {
            discountService.giveBack(booking.getDiscountCodeId());
            booking.clearDiscountRedeemed();
        }
    }

    // =====================================================================================
    // 6.3 Cancel (customer)
    // =====================================================================================

    private record RefundPlan(Long bookingId, Long paymentId, String gatewayRef, BigDecimal amount, int percent) {
    }

    public BookingResponse cancel(Long userId, Long bookingId) {
        RefundPlan plan = tx.execute(status -> {
            Booking booking = bookingRepository.lockByIdAndUserId(bookingId, userId)
                    .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found"));
            Instant now = dbClock.now();
            return switch (booking.effectiveStatus(now)) {
                case HELD -> {
                    seatHold.release(bookingId);
                    booking.cancel();
                    yield null;                      // no money involved
                }
                case PAYMENT_PENDING -> throw new AppException(ErrorCode.PAYMENT_IN_PROGRESS,
                        "Payment is in progress; try again in a moment");
                case EXPIRED, CANCELLED -> throw new AppException(ErrorCode.INVALID_BOOKING_STATE,
                        "Booking is already " + booking.effectiveStatus(now));
                case CONFIRMED -> {
                    ShowDetails show = showService.details(booking.getShowId());
                    if (!show.startTime().isAfter(now)) {
                        throw new AppException(ErrorCode.CANCELLATION_NOT_ALLOWED, "The show has already started");
                    }
                    double hoursBefore = Duration.between(now, show.startTime()).toSeconds() / 3600.0;
                    int percent = refundPolicyService.refundPercent(show.refundPolicyId(), hoursBefore);
                    yield refundConfirmed(booking, show, percent, RefundReason.CUSTOMER_CANCEL,
                            EventType.BOOKING_CANCELLED);
                }
            };
        });
        evictSeatMapOf(bookingId);
        settleRefund(plan);
        return queries.get(userId, bookingId);
    }

    /** Inside a transaction: release seats, cancel, drop the reminder, record the refund on the booking, write the event. */
    private RefundPlan refundConfirmed(Booking booking, ShowDetails show, int percent, RefundReason reason,
                                       EventType event) {
        BigDecimal amount = booking.getTotalAmount().multiply(BigDecimal.valueOf(percent))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        seatHold.release(booking.getId());
        booking.cancel();
        notifications.cancelReminder(booking);
        Payment payment = paymentService.successfulPayment(booking.getId())
                .orElseThrow(() -> new IllegalStateException("Confirmed booking " + booking.getId() + " has no payment"));
        booking.startRefund(amount, percent, reason);
        notifications.publish(event, booking, show, BookingNotifications.refundExtra(amount, percent));
        return amount.signum() > 0
                ? new RefundPlan(booking.getId(), payment.getId(), payment.getGatewayRef(), amount, percent)
                : null;                               // zero-amount refund: nothing to send to the gateway
    }

    /** Gateway refund (no TX), then record the outcome. A failed refund stays visible to admins. */
    private void settleRefund(RefundPlan plan) {
        if (plan == null) {
            return;
        }
        RefundResult result = paymentService.refundAtGateway(plan.gatewayRef(), plan.amount());
        tx.executeWithoutResult(status -> {
            Booking booking = bookingRepository.lockById(plan.bookingId()).orElseThrow();
            booking.completeRefund(result.success(), result.gatewayRef());
            if (result.success()) {
                paymentService.markRefunded(plan.paymentId(), plan.percent() == FULL_REFUND);
                notifications.publish(EventType.REFUND_PROCESSED, booking, showService.details(booking.getShowId()),
                        BookingNotifications.refundExtra(plan.amount(), plan.percent()));
            } else {
                log.warn("Refund for booking {} failed: {}", plan.bookingId(), result.failureReason());
            }
        });
    }

    // =====================================================================================
    // 6.4 Admin cancels a show: 100% refunds, one transaction per booking, safe to re-run
    // =====================================================================================

    public ShowCancellationResult cancelShow(Long showId) {
        boolean newlyCancelled = showService.markCancelled(showId);
        List<Long> ids = bookingRepository.findIdsByShowIdAndStatusIn(showId,
                List.of(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED));
        List<Long> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                cancelForShow(id);
            } catch (RuntimeException e) {
                log.error("Cancelling booking {} of show {} failed; re-run to retry", id, showId, e);
                failed.add(id);
            }
        }
        ShowDetails show = showService.details(showId);
        SeatLayout layout = screenService.layout(show.screenId(), show.layoutVersion());
        seatHold.releaseShow(showId, layout.seats().stream().map(SeatLayout.LayoutSeat::id).toList());
        seatMap.evict(showId);
        return new ShowCancellationResult(showId, newlyCancelled, ids.size() - failed.size(), failed);
    }

    public void cancelForShow(Long bookingId) {
        RefundPlan plan = tx.execute(status -> {
            Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
            Instant now = dbClock.now();
            ShowDetails show = showService.details(booking.getShowId());
            return switch (booking.effectiveStatus(now)) {
                case EXPIRED -> {
                    if (booking.getStatus() == BookingStatus.HELD) {
                        booking.expire();
                        seatHold.release(bookingId);
                    }
                    yield null;
                }
                case HELD, PAYMENT_PENDING -> {
                    // A PAYMENT_PENDING booking's confirm will now fail and auto-refund.
                    seatHold.release(bookingId);
                    booking.cancel();
                    yield null;
                }
                case CONFIRMED -> refundConfirmed(booking, show, FULL_REFUND, RefundReason.SHOW_CANCELLED,
                        EventType.SHOW_CANCELLED);
                case CANCELLED -> null;
            };
        });
        settleRefund(plan);
    }

    private void evictSeatMapOf(Long bookingId) {
        bookingRepository.findById(bookingId).ifPresent(b -> seatMap.evict(b.getShowId()));
    }
}
