package com.moviebooking.controller.pricing;

import com.moviebooking.dto.pricing.DiscountCodeRequest;
import com.moviebooking.dto.pricing.DiscountCodeResponse;
import com.moviebooking.service.pricing.DiscountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Discount codes. (Prices live on each show: see AdminShowController.) */
@RestController
@RequestMapping("/api/admin")
public class AdminPricingController {

    private final DiscountService discountService;

    public AdminPricingController(DiscountService discountService) {
        this.discountService = discountService;
    }

    @GetMapping("/discounts")
    public List<DiscountCodeResponse> discounts() {
        return discountService.list();
    }

    @GetMapping("/discounts/{id}")
    public DiscountCodeResponse discount(@PathVariable Long id) {
        return discountService.get(id);
    }

    @PostMapping("/discounts")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountCodeResponse createDiscount(@Valid @RequestBody DiscountCodeRequest request) {
        return discountService.create(request);
    }

    @PutMapping("/discounts/{id}")
    public DiscountCodeResponse updateDiscount(@PathVariable Long id, @Valid @RequestBody DiscountCodeRequest request) {
        return discountService.update(id, request);
    }

    @DeleteMapping("/discounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateDiscount(@PathVariable Long id) {
        discountService.deactivate(id);
    }
}
