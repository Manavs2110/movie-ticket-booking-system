package com.moviebooking.support;

import com.moviebooking.service.payment.internal.MockPaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;

/**
 * The mock gateway plus a hook that runs just before a charge, i.e. after the pay "prepare" transaction
 * committed and before "confirm". Lets tests simulate what can happen while money is in flight.
 */
public class HookedPaymentGateway extends MockPaymentGateway {

    private volatile Runnable beforeCharge = () -> { };

    public void beforeCharge(Runnable hook) {
        this.beforeCharge = hook;
    }

    public void reset() {
        this.beforeCharge = () -> { };
        failNextRefunds(0);
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String idempotencyKey, String paymentToken) {
        beforeCharge.run();
        return super.charge(amount, idempotencyKey, paymentToken);
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        HookedPaymentGateway hookedPaymentGateway() {
            return new HookedPaymentGateway();
        }
    }
}
