package com.moviebooking.dto.refundpolicy;

import com.moviebooking.model.refundpolicy.RefundPolicy;
import com.moviebooking.model.refundpolicy.RefundRule;

import java.util.Comparator;
import java.util.List;

public record RefundPolicyResponse(Long id, String name, boolean isDefault, List<Rule> rules) {

    public record Rule(int minHoursBefore, int refundPercent) {
    }

    public static RefundPolicyResponse from(RefundPolicy p) {
        return new RefundPolicyResponse(p.getId(), p.getName(), p.isDefaultPolicy(), p.getRules().stream()
                .sorted(Comparator.comparingInt(RefundRule::minHoursBefore).reversed())
                .map(r -> new Rule(r.minHoursBefore(), r.refundPercent()))
                .toList());
    }
}
