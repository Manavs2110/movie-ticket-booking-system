package com.moviebooking.dto.booking;

import jakarta.validation.constraints.Size;

/** Optional body. {@code paymentToken} is the client-side card token; the mock gateway declines "tok_decline". */
public record PayRequest(@Size(max = 100) String paymentToken) {
}
