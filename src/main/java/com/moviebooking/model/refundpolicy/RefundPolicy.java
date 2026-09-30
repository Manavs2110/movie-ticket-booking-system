package com.moviebooking.model.refundpolicy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "refund_policy")
public class RefundPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPolicy;

    /** JSONB array on the policy row (no separate rule table): [{"minHoursBefore":24,"refundPercent":100}, …] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<RefundRule> rules = new ArrayList<>();

    protected RefundPolicy() {
    }

    public RefundPolicy(String name) {
        this.name = name;
    }

    /**
     * Rules sorted by threshold, highest first; the first one the cancellation time meets wins.
     * Example [24h → 100, 4h → 50, 0h → 0]: 30h → 100, exactly 24h → 100, 5h → 50, 1h → 0.
     */
    public int refundPercent(double hoursBeforeShow) {
        if (hoursBeforeShow < 0) {
            return 0;
        }
        return rules.stream()
                .sorted(Comparator.comparingInt(RefundRule::minHoursBefore).reversed())
                .filter(r -> hoursBeforeShow >= r.minHoursBefore())
                .map(RefundRule::refundPercent)
                .findFirst()
                .orElse(0);
    }

    public void replaceRules(String name, List<RefundRule> newRules) {
        this.name = name;
        this.rules = new ArrayList<>(newRules);
    }

    public void setDefaultPolicy(boolean defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDefaultPolicy() {
        return defaultPolicy;
    }

    public List<RefundRule> getRules() {
        return rules;
    }
}
