package com.moviebooking.service.refundpolicy.internal;

import com.moviebooking.model.refundpolicy.RefundPolicy;
import com.moviebooking.model.refundpolicy.RefundRule;
import com.moviebooking.repository.refundpolicy.RefundPolicyRepository;
import com.moviebooking.service.refundpolicy.RefundPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefundPolicyTest {

    private static RefundPolicy standard() {
        RefundPolicy p = new RefundPolicy("Standard");
        // deliberately unsorted: the policy sorts by threshold itself
        p.replaceRules("Standard", List.of(new RefundRule(4, 50), new RefundRule(0, 0), new RefundRule(24, 100)));
        return p;
    }

    @ParameterizedTest(name = "{0}h before → {1}%")
    @CsvSource({"72, 100", "24.0, 100", "23.99, 50", "4.0, 50", "3.99, 0", "0, 0", "-1, 0"})
    void ruleBoundaries(double hours, int expectedPercent) {
        assertThat(standard().refundPercent(hours)).isEqualTo(expectedPercent);
    }

    @Test
    void noMatchingRuleMeansNoRefund() {
        RefundPolicy p = new RefundPolicy("Strict");
        p.replaceRules("Strict", List.of(new RefundRule(48, 100)));
        assertThat(p.refundPercent(10)).isZero();
    }

    @Test
    void theaterOverrideWinsOverDefault() {
        RefundPolicyRepository repo = mock(RefundPolicyRepository.class);
        RefundPolicy flexible = new RefundPolicy("Flexible");
        flexible.replaceRules("Flexible", List.of(new RefundRule(2, 100), new RefundRule(0, 25)));
        when(repo.findById(7L)).thenReturn(Optional.of(flexible));
        when(repo.findFirstByDefaultPolicyTrue()).thenReturn(Optional.of(standard()));
        RefundPolicyService service = new RefundPolicyServiceImpl(repo);

        assertThat(service.refundPercent(7L, 3)).isEqualTo(100);    // theater override
        assertThat(service.refundPercent(null, 3)).isZero();        // global default
        assertThat(service.refundPercent(99L, 30)).isEqualTo(100);  // unknown override → default
    }

    @Test
    void noPolicyAtAllMeansNoRefund() {
        RefundPolicyRepository repo = mock(RefundPolicyRepository.class);
        when(repo.findFirstByDefaultPolicyTrue()).thenReturn(Optional.empty());
        assertThat(new RefundPolicyServiceImpl(repo).refundPercent(null, 100)).isZero();
    }
}
