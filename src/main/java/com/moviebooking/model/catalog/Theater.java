package com.moviebooking.model.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "theater")
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "city_id", nullable = false)
    private Long cityId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Optional override; null means the global default refund policy applies. */
    @Column(name = "refund_policy_id")
    private Long refundPolicyId;

    @Column(nullable = false)
    private boolean active = true;

    protected Theater() {
    }

    public Theater(Long cityId, String name, String address, BigDecimal latitude, BigDecimal longitude,
                   Long refundPolicyId) {
        this.cityId = cityId;
        update(name, address, latitude, longitude, refundPolicyId, true);
    }

    public void update(String name, String address, BigDecimal latitude, BigDecimal longitude,
                       Long refundPolicyId, boolean active) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.refundPolicyId = refundPolicyId;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public Long getCityId() {
        return cityId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public Long getRefundPolicyId() {
        return refundPolicyId;
    }

    public boolean isActive() {
        return active;
    }
}
