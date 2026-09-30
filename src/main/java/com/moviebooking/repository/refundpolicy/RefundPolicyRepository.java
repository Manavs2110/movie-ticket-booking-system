package com.moviebooking.repository.refundpolicy;

import com.moviebooking.model.refundpolicy.RefundPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefundPolicyRepository extends JpaRepository<RefundPolicy, Long> {

    Optional<RefundPolicy> findFirstByDefaultPolicyTrue();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefundPolicy p set p.defaultPolicy = false where p.defaultPolicy = true")
    int clearDefault();
}
