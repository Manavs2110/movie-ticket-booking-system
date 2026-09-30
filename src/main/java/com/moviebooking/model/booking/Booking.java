package com.moviebooking.model.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.moviebooking.model.payment.RefundReason;
import com.moviebooking.model.payment.RefundStatus;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "booking")
public class Booking implements Persistable<Long> {

    /**
     * Assigned from {@code nextval('booking_id_seq')} before the Redis filter runs, so the Redis lock value
     * and the booking row share one id (LLD §6.1).
     */
    @Id
    private Long id;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "discount_code_id")
    private Long discountCodeId;

    /** True once the discount code's use was counted at payment (the per-user limit counts these). */
    @Column(name = "discount_redeemed", nullable = false)
    private boolean discountRedeemed;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    // ---- refund: at most one per booking, so it lives here (all null = no refund)
    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_percent")
    private Integer refundPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_reason")
    private RefundReason refundReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status")
    private RefundStatus refundStatus;

    @Column(name = "refund_gateway_ref")
    private String refundGatewayRef;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected Booking() {
    }

    public Booking(Long id, Long userId, Long showId, BigDecimal subtotal, BigDecimal discountAmount,
                   BigDecimal totalAmount, Long discountCodeId, Instant holdExpiresAt) {
        this.id = id;
        this.userId = userId;
        this.showId = showId;
        this.status = BookingStatus.HELD;
        this.subtotal = subtotal;
        this.discountAmount = discountAmount;
        this.totalAmount = totalAmount;
        this.discountCodeId = discountCodeId;
        this.holdExpiresAt = holdExpiresAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public BookingStatus effectiveStatus(Instant dbNow) {
        return BookingStatus.effective(status, holdExpiresAt, dbNow);
    }

    /** @param lockedUntil the extended Postgres lock end (at least the payment window from now) */
    public void startPayment(Instant lockedUntil) {
        holdExpiresAt = lockedUntil;
        status = BookingStatus.PAYMENT_PENDING;
    }

    public void backToHeld() {
        status = BookingStatus.HELD;
    }

    public void confirm() {
        status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        status = BookingStatus.CANCELLED;
    }

    public void expire() {
        status = BookingStatus.EXPIRED;
    }

    public void markDiscountRedeemed() {
        discountRedeemed = true;
    }

    public void clearDiscountRedeemed() {
        discountRedeemed = false;
    }

    /** Records the refund owed. A zero amount needs no gateway call, so it's complete immediately. */
    public void startRefund(BigDecimal amount, int percent, RefundReason reason) {
        refundAmount = amount;
        refundPercent = percent;
        refundReason = reason;
        refundStatus = amount.signum() == 0 ? RefundStatus.SUCCESS : RefundStatus.PENDING;
        if (refundStatus == RefundStatus.SUCCESS) {
            refundedAt = Instant.now();
        }
    }

    public void completeRefund(boolean success, String gatewayRef) {
        refundStatus = success ? RefundStatus.SUCCESS : RefundStatus.FAILED;
        refundGatewayRef = gatewayRef;
        if (success) {
            refundedAt = Instant.now();
        }
    }

    public boolean hasRefund() {
        return refundStatus != null;
    }

    @Override
    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getShowId() {
        return showId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Long getDiscountCodeId() {
        return discountCodeId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public boolean isDiscountRedeemed() {
        return discountRedeemed;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public Integer getRefundPercent() {
        return refundPercent;
    }

    public RefundReason getRefundReason() {
        return refundReason;
    }

    public RefundStatus getRefundStatus() {
        return refundStatus;
    }

    public String getRefundGatewayRef() {
        return refundGatewayRef;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
