package com.moviebooking.service.pricing.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.DbClock;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.model.pricing.DiscountCode;
import com.moviebooking.model.pricing.DiscountQuote;
import com.moviebooking.model.pricing.DiscountType;
import com.moviebooking.repository.pricing.DiscountCodeRepository;
import com.moviebooking.service.pricing.DiscountService;
import com.moviebooking.service.pricing.DiscountUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscountServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private final DiscountCodeRepository repository = mock(DiscountCodeRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DbClock clock = mock(DbClock.class);
    private final DiscountUsage usage = mock(DiscountUsage.class);
    private final DiscountService service = new DiscountServiceImpl(repository, jdbc, clock, usage);

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
        when(usage.redeemedBy(any(), any())).thenReturn(0);
    }

    private DiscountCode stub(DiscountType type, String value, String minOrder, Instant from, Instant to) {
        DiscountCode dc = new DiscountCode("SAVE");
        dc.update(type, new BigDecimal(value), null, new BigDecimal(minOrder), from, to, null, 1, true);
        when(repository.findByCode("SAVE")).thenReturn(Optional.of(dc));
        return dc;
    }

    @Test
    void previewNormalizesCodeAndComputesAmount() {
        stub(DiscountType.FLAT, "75", "100", NOW.minusSeconds(60), NOW.plusSeconds(60));
        DiscountQuote quote = service.preview("  save ", 1L, new BigDecimal("400.00"));
        assertThat(quote.code()).isEqualTo("SAVE");
        assertThat(quote.amount()).isEqualByComparingTo("75.00");
    }

    @Test
    void unknownCodeIsInvalid() {
        when(repository.findByCode("NOPE")).thenReturn(Optional.empty());
        assertInvalid(() -> service.preview("nope", 1L, BigDecimal.TEN));
    }

    @Test
    void belowMinimumOrderIsInvalid() {
        stub(DiscountType.FLAT, "75", "500", NOW.minusSeconds(60), NOW.plusSeconds(60));
        assertInvalid(() -> service.preview("SAVE", 1L, new BigDecimal("499.99")));
    }

    @Test
    void expiredOrNotYetStartedIsInvalid() {
        stub(DiscountType.PERCENT, "10", "0", NOW.minusSeconds(120), NOW.minusSeconds(60));
        assertInvalid(() -> service.preview("SAVE", 1L, BigDecimal.TEN));
        stub(DiscountType.PERCENT, "10", "0", NOW.plusSeconds(60), NOW.plusSeconds(120));
        assertInvalid(() -> service.preview("SAVE", 1L, BigDecimal.TEN));
    }

    @Test
    void perUserLimitReachedIsInvalid() {
        stub(DiscountType.FLAT, "10", "0", NOW.minusSeconds(60), NOW.plusSeconds(60));
        when(usage.redeemedBy(any(), any())).thenReturn(1);
        assertInvalid(() -> service.preview("SAVE", 1L, new BigDecimal("100")));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.INVALID_DISCOUNT);
    }
}
