package com.moviebooking.controller.refundpolicy;

import com.moviebooking.dto.refundpolicy.RefundPolicyRequest;
import com.moviebooking.dto.refundpolicy.RefundPolicyResponse;
import com.moviebooking.service.refundpolicy.RefundPolicyService;
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

@RestController
@RequestMapping("/api/admin/refund-policies")
public class AdminRefundPolicyController {

    private final RefundPolicyService service;

    public AdminRefundPolicyController(RefundPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<RefundPolicyResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public RefundPolicyResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RefundPolicyResponse create(@Valid @RequestBody RefundPolicyRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public RefundPolicyResponse update(@PathVariable Long id, @Valid @RequestBody RefundPolicyRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
