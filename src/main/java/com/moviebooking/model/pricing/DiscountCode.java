package com.moviebooking.model.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "discount_code")
public class DiscountCode {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType type;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal value;

    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "min_order", nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrder;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    /** null = unlimited. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(nullable = false)
    private boolean active = true;

    protected DiscountCode() {
    }

    public DiscountCode(String code) {
        this.code = code;
    }

    public void update(DiscountType type, BigDecimal value, BigDecimal maxDiscount, BigDecimal minOrder,
                       Instant validFrom, Instant validTo, Integer usageLimit, int perUserLimit, boolean active) {
        this.type = type;
        this.value = value;
        this.maxDiscount = maxDiscount;
        this.minOrder = minOrder;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.usageLimit = usageLimit;
        this.perUserLimit = perUserLimit;
        this.active = active;
    }

    public boolean isCurrentlyValid(Instant now) {
        return active && !now.isBefore(validFrom) && now.isBefore(validTo);
    }

    public boolean isUsedUp() {
        return usageLimit != null && usedCount >= usageLimit;
    }

    /** FLAT → min(value, subtotal); PERCENT → min(subtotal × value / 100, cap). Never more than the subtotal. */
    public BigDecimal discountFor(BigDecimal subtotal) {
        BigDecimal discount = type == DiscountType.FLAT
                ? value
                : subtotal.multiply(value).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        if (type == DiscountType.PERCENT && maxDiscount != null) {
            discount = discount.min(maxDiscount);
        }
        return discount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public DiscountType getType() {
        return type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getMaxDiscount() {
        return maxDiscount;
    }

    public BigDecimal getMinOrder() {
        return minOrder;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public boolean isActive() {
        return active;
    }
}
