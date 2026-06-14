package com.example.smartdoc.payment.config;

import com.example.smartdoc.payment.model.Plan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
    @NotBlank String provider,
    @NotBlank String checkoutMode,
    @NotBlank String checkoutBaseUrl,
    String checkoutReturnUrl,
    String checkoutCancelUrl,
    String stripeSecretKey,
    @NotBlank String stripeWebhookSecret,
    @NotBlank String internalToken,
    @Min(1) int webhookToleranceSeconds,
    @Valid List<PlanConfig> additionalPlans,
    @Valid @NotNull PlanConfig defaultPlan
) {
    public boolean useStripeCheckout() {
        return "stripe".equalsIgnoreCase(checkoutMode);
    }

    public record PlanConfig(
        @NotBlank String id,
        @NotBlank String name,
        @Min(1) int priceCents,
        @NotBlank String currency,
        @NotBlank String interval,
        boolean active
    ) {
        public Plan toDomain() {
            return new Plan(id, name, priceCents, currency, interval, active);
        }
    }
}

