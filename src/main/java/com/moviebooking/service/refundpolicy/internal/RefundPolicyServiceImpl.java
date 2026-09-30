package com.moviebooking.service.refundpolicy.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.dto.refundpolicy.RefundPolicyRequest;
import com.moviebooking.dto.refundpolicy.RefundPolicyResponse;
import com.moviebooking.model.refundpolicy.RefundPolicy;
import com.moviebooking.model.refundpolicy.RefundRule;
import com.moviebooking.repository.refundpolicy.RefundPolicyRepository;
import com.moviebooking.service.refundpolicy.RefundPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Picks the applicable policy (theater override → global default) and computes the refund % (LLD §7.3). */
@Service
public class RefundPolicyServiceImpl implements RefundPolicyService {

    private final RefundPolicyRepository repository;

    public RefundPolicyServiceImpl(RefundPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * @param theaterPolicyId the theater's override, or null
     * @param hoursBeforeShow fractional hours from now until the show starts
     */
    @Transactional(readOnly = true)
    public int refundPercent(Long theaterPolicyId, double hoursBeforeShow) {
        RefundPolicy policy = theaterPolicyId != null
                ? repository.findById(theaterPolicyId).orElse(null)
                : null;
        if (policy == null) {
            policy = repository.findFirstByDefaultPolicyTrue().orElse(null);
        }
        return policy == null ? 0 : policy.refundPercent(hoursBeforeShow);
    }

    @Transactional(readOnly = true)
    public List<RefundPolicyResponse> list() {
        return repository.findAll().stream().map(RefundPolicyResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RefundPolicyResponse get(Long id) {
        return RefundPolicyResponse.from(entity(id));
    }

    @Transactional
    public RefundPolicyResponse create(RefundPolicyRequest request) {
        List<RefundRule> rules = toRules(request);
        if (request.makeDefault()) {
            repository.clearDefault();
        }
        RefundPolicy policy = new RefundPolicy(request.name().trim());
        policy.replaceRules(request.name().trim(), rules);
        policy.setDefaultPolicy(request.makeDefault());
        return RefundPolicyResponse.from(repository.save(policy));
    }

    @Transactional
    public RefundPolicyResponse update(Long id, RefundPolicyRequest request) {
        List<RefundRule> rules = toRules(request);
        if (request.makeDefault() && !entity(id).isDefaultPolicy()) {
            repository.clearDefault();   // clears the persistence context, so re-load below
        }
        RefundPolicy policy = entity(id);
        policy.replaceRules(request.name().trim(), rules);
        if (request.makeDefault()) {
            policy.setDefaultPolicy(true);
        }
        repository.flush();
        return RefundPolicyResponse.from(policy);
    }

    @Transactional
    public void delete(Long id) {
        RefundPolicy policy = entity(id);
        if (policy.isDefaultPolicy()) {
            throw new AppException(ErrorCode.CONFLICT, "The default policy can't be deleted; make another one default first");
        }
        repository.delete(policy);
        repository.flush();   // a theater still using it → FK violation → 409
    }

    @Transactional(readOnly = true)
    public void requireExists(Long id) {
        if (!repository.existsById(id)) {
            throw AppException.notFound("Refund policy", id);
        }
    }

    private RefundPolicy entity(Long id) {
        return repository.findById(id).orElseThrow(() -> AppException.notFound("Refund policy", id));
    }

    private static List<RefundRule> toRules(RefundPolicyRequest request) {
        Set<Integer> thresholds = new HashSet<>();
        for (RefundPolicyRequest.Rule r : request.rules()) {
            if (!thresholds.add(r.minHoursBefore())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Duplicate threshold " + r.minHoursBefore() + "h");
            }
        }
        return request.rules().stream().map(r -> new RefundRule(r.minHoursBefore(), r.refundPercent())).toList();
    }
}
