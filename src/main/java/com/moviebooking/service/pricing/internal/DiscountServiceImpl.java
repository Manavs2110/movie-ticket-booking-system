package com.moviebooking.service.pricing.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.dto.pricing.DiscountCodeRequest;
import com.moviebooking.dto.pricing.DiscountCodeResponse;
import com.moviebooking.model.pricing.DiscountCode;
import com.moviebooking.model.pricing.DiscountQuote;
import com.moviebooking.model.pricing.DiscountType;
import com.moviebooking.repository.pricing.DiscountCodeRepository;
import com.moviebooking.service.pricing.DiscountService;
import com.moviebooking.service.pricing.DiscountUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Discount codes (LLD §7.2).
 * <ul>
 *   <li>{@link #preview}: validated at hold time, nothing counted.</li>
 *   <li>{@link #redeem}: counted atomically at pay time, before the charge.</li>
 *   <li>{@link #giveBack}: undone if the charge is declined or the booking fails to confirm.</li>
 * </ul>
 */
@Service
public class DiscountServiceImpl implements DiscountService {

    private final DiscountCodeRepository repository;
    private final JdbcTemplate jdbc;
    private final DbClock dbClock;
    private final DiscountUsage discountUsage;

    public DiscountServiceImpl(DiscountCodeRepository repository, JdbcTemplate jdbc, DbClock dbClock,
                               DiscountUsage discountUsage) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.dbClock = dbClock;
        this.discountUsage = discountUsage;
    }

    @Transactional(readOnly = true)
    public DiscountQuote preview(String rawCode, Long userId, BigDecimal subtotal) {
        String code = normalize(rawCode);
        DiscountCode dc = repository.findByCode(code).orElseThrow(() -> invalid("Unknown discount code"));
        Instant now = dbClock.now();
        if (!dc.isCurrentlyValid(now)) {
            throw invalid("Discount code is not active");
        }
        if (subtotal.compareTo(dc.getMinOrder()) < 0) {
            throw invalid("Minimum order for this code is " + dc.getMinOrder());
        }
        if (dc.isUsedUp()) {
            throw invalid("Discount code has been fully used");
        }
        if (discountUsage.redeemedBy(userId, dc.getId()) >= dc.getPerUserLimit()) {
            throw invalid("You have already used this code the maximum number of times");
        }
        return new DiscountQuote(dc.getId(), dc.getCode(), dc.discountFor(subtotal));
    }

    /**
     * Counts one use. The conditional UPDATE locks the code's row until the payment transaction commits, so the
     * per-user check that follows (bookings flagged {@code discount_redeemed}) runs one-at-a-time for that code.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void redeem(Long codeId, Long userId) {
        int updated = jdbc.update("""
                UPDATE discount_code SET used_count = used_count + 1
                WHERE id = ? AND active AND now() >= valid_from AND now() < valid_to
                  AND (usage_limit IS NULL OR used_count < usage_limit)
                """, codeId);
        if (updated == 0) {
            throw invalid("Discount code is no longer available");
        }
        Integer perUserLimit = jdbc.queryForObject("SELECT per_user_limit FROM discount_code WHERE id = ?",
                Integer.class, codeId);
        if (discountUsage.redeemedBy(userId, codeId) >= perUserLimit) {
            throw invalid("You have already used this code the maximum number of times");
        }
    }

    /** Gives one use back; the caller clears the booking's flag, so it's called at most once per redemption. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void giveBack(Long codeId) {
        jdbc.update("UPDATE discount_code SET used_count = used_count - 1 WHERE id = ? AND used_count > 0", codeId);
    }

    @Transactional(readOnly = true)
    public String codeOf(Long id) {
        return repository.findById(id).map(DiscountCode::getCode).orElse(null);
    }

    // ---------- admin ----------

    @Transactional(readOnly = true)
    public List<DiscountCodeResponse> list() {
        return repository.findAll().stream().map(DiscountCodeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DiscountCodeResponse get(Long id) {
        return DiscountCodeResponse.from(entity(id));
    }

    @Transactional
    public DiscountCodeResponse create(DiscountCodeRequest r) {
        String code = normalize(r.code());
        if (repository.existsByCode(code)) {
            throw new AppException(ErrorCode.CONFLICT, "Discount code already exists");
        }
        DiscountCode dc = new DiscountCode(code);
        apply(dc, r);
        return DiscountCodeResponse.from(repository.saveAndFlush(dc));
    }

    @Transactional
    public DiscountCodeResponse update(Long id, DiscountCodeRequest r) {
        DiscountCode dc = entity(id);
        if (!dc.getCode().equals(normalize(r.code()))) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "A discount code's text can't be changed");
        }
        apply(dc, r);
        repository.flush();
        return DiscountCodeResponse.from(dc);
    }

    /** Codes are deactivated, never deleted: bookings and redemptions reference them. */
    @Transactional
    public void deactivate(Long id) {
        DiscountCode dc = entity(id);
        dc.update(dc.getType(), dc.getValue(), dc.getMaxDiscount(), dc.getMinOrder(), dc.getValidFrom(),
                dc.getValidTo(), dc.getUsageLimit(), dc.getPerUserLimit(), false);
    }

    private void apply(DiscountCode dc, DiscountCodeRequest r) {
        if (r.type() == DiscountType.PERCENT && r.value().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "A percentage discount can't exceed 100");
        }
        if (!r.validTo().isAfter(r.validFrom())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "validTo must be after validFrom");
        }
        if (r.usageLimit() != null && r.usageLimit() < dc.getUsedCount()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "usageLimit is below the number of uses so far");
        }
        dc.update(r.type(), r.value(), r.maxDiscount(), r.minOrder() == null ? BigDecimal.ZERO : r.minOrder(),
                r.validFrom().toInstant(), r.validTo().toInstant(), r.usageLimit(),
                r.perUserLimit() == null ? 1 : r.perUserLimit(), r.active() == null || r.active());
    }


    private DiscountCode entity(Long id) {
        return repository.findById(id).orElseThrow(() -> AppException.notFound("Discount code", id));
    }

    static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_DISCOUNT, message);
    }
}
