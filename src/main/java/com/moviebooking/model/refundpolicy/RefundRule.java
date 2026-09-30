package com.moviebooking.model.refundpolicy;

/**
 * "Cancelled at least {@code minHoursBefore} hours before the show → refund {@code refundPercent}%."
 * Stored as one element of the policy's {@code rules} JSON array, e.g. {"minHoursBefore":24,"refundPercent":100}.
 */
public record RefundRule(int minHoursBefore, int refundPercent) {
}
