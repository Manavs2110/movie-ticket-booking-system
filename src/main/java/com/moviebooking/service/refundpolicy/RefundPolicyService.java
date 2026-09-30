package com.moviebooking.service.refundpolicy;

import com.moviebooking.dto.refundpolicy.RefundPolicyRequest;
import com.moviebooking.dto.refundpolicy.RefundPolicyResponse;

import java.util.List;

/** Picks the applicable policy (theater override → global default) and computes the refund % (LLD §7.3). */
public interface RefundPolicyService {

    /**
     * @param theaterPolicyId the theater's override, or null
     * @param hoursBeforeShow fractional hours from now until the show starts
     */
    int refundPercent(Long theaterPolicyId, double hoursBeforeShow);

    List<RefundPolicyResponse> list();

    RefundPolicyResponse get(Long id);

    RefundPolicyResponse create(RefundPolicyRequest request);

    RefundPolicyResponse update(Long id, RefundPolicyRequest request);

    void delete(Long id);

    void requireExists(Long id);
}
