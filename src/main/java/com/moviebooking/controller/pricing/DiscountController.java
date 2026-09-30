package com.moviebooking.controller.pricing;

import com.moviebooking.common.CurrentUser;
import com.moviebooking.dto.pricing.DiscountPreviewRequest;
import com.moviebooking.dto.pricing.PriceBreakdown;
import com.moviebooking.service.pricing.CheckoutPricing;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/discounts")
public class DiscountController {

    private final CheckoutPricing checkoutPricing;

    public DiscountController(CheckoutPricing checkoutPricing) {
        this.checkoutPricing = checkoutPricing;
    }

    /** Price breakdown with the code applied. Nothing is held or counted. */
    @PostMapping("/preview")
    public PriceBreakdown preview(@Valid @RequestBody DiscountPreviewRequest request) {
        return checkoutPricing.preview(request.showId(), request.seatIds(), request.code(), CurrentUser.id());
    }
}
