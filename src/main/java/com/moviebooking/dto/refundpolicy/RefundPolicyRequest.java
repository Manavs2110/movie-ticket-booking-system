package com.moviebooking.dto.refundpolicy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RefundPolicyRequest(
        @NotBlank @Size(max = 100) String name,
        boolean makeDefault,
        @NotEmpty @Size(max = 20) @Valid List<Rule> rules) {

    public record Rule(@Min(0) @Max(8760) int minHoursBefore, @Min(0) @Max(100) int refundPercent) {
    }
}
