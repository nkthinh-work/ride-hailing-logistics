package com.rhl.pricing.configs;

import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Binds provisional development & testing pricing configuration from application.yaml.
 * <p><strong>NOTE:</strong> This is PROVISIONAL_DEV_CONFIG and not an official business rate card.
 */
@Configuration
@ConfigurationProperties(prefix = "rhl.pricing")
public class PricingProperties {

    private boolean devFallbackEnabled = false;
    private String defaultCurrency = "VND";
    private String defaultRuleVersion = "DEV-v1.0";
    private Map<VehicleType, PolicyProperties> policies = new EnumMap<>(VehicleType.class);
    private QuoteProperties quote = new QuoteProperties();
    private SurgeProperties surge = new SurgeProperties();

    public QuoteProperties getQuote() {
        return quote;
    }

    public void setQuote(QuoteProperties quote) {
        this.quote = quote;
    }

    public SurgeProperties getSurge() {
        return surge;
    }

    public void setSurge(SurgeProperties surge) {
        this.surge = surge;
    }

    public static class QuoteProperties {
        private long ttlSeconds = 300;
        private boolean allowSimulatedRouting = true;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public boolean isAllowSimulatedRouting() {
            return allowSimulatedRouting;
        }

        public void setAllowSimulatedRouting(boolean allowSimulatedRouting) {
            this.allowSimulatedRouting = allowSimulatedRouting;
        }
    }

    public static class SurgeProperties {
        private boolean enabled = false;
        private boolean allowSimulatedSurge = true;
        private String policyVersion = "SURGE-DEV-v1.0";
        private BigDecimal minMultiplier = BigDecimal.valueOf(1.00);
        private BigDecimal maxMultiplier = BigDecimal.valueOf(2.50);
        private BigDecimal thresholdRatio = BigDecimal.valueOf(1.00);
        private BigDecimal sensitivity = BigDecimal.valueOf(0.50);
        private int timeWindowMinutes = 15;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAllowSimulatedSurge() {
            return allowSimulatedSurge;
        }

        public void setAllowSimulatedSurge(boolean allowSimulatedSurge) {
            this.allowSimulatedSurge = allowSimulatedSurge;
        }

        public String getPolicyVersion() {
            return policyVersion;
        }

        public void setPolicyVersion(String policyVersion) {
            this.policyVersion = policyVersion;
        }

        public BigDecimal getMinMultiplier() {
            return minMultiplier;
        }

        public void setMinMultiplier(BigDecimal minMultiplier) {
            this.minMultiplier = minMultiplier;
        }

        public BigDecimal getMaxMultiplier() {
            return maxMultiplier;
        }

        public void setMaxMultiplier(BigDecimal maxMultiplier) {
            this.maxMultiplier = maxMultiplier;
        }

        public BigDecimal getThresholdRatio() {
            return thresholdRatio;
        }

        public void setThresholdRatio(BigDecimal thresholdRatio) {
            this.thresholdRatio = thresholdRatio;
        }

        public BigDecimal getSensitivity() {
            return sensitivity;
        }

        public void setSensitivity(BigDecimal sensitivity) {
            this.sensitivity = sensitivity;
        }

        public int getTimeWindowMinutes() {
            return timeWindowMinutes;
        }

        public void setTimeWindowMinutes(int timeWindowMinutes) {
            this.timeWindowMinutes = timeWindowMinutes;
        }

        public com.rhl.pricing.surge.SurgePolicy toPolicy() {
            return new com.rhl.pricing.surge.SurgePolicy(
                    "SURGE-CONFIG-POLICY",
                    policyVersion,
                    minMultiplier != null ? minMultiplier : BigDecimal.valueOf(1.00),
                    maxMultiplier != null ? maxMultiplier : BigDecimal.valueOf(2.50),
                    thresholdRatio != null ? thresholdRatio : BigDecimal.valueOf(1.00),
                    sensitivity != null ? sensitivity : BigDecimal.valueOf(0.50),
                    timeWindowMinutes > 0 ? timeWindowMinutes : 15,
                    2,
                    java.math.RoundingMode.HALF_UP
            );
        }
    }

    public boolean isDevFallbackEnabled() {
        return devFallbackEnabled;
    }

    public void setDevFallbackEnabled(boolean devFallbackEnabled) {
        this.devFallbackEnabled = devFallbackEnabled;
    }

    public static class PolicyProperties {
        private BigDecimal baseFare;
        private BigDecimal includedDistanceKm = BigDecimal.valueOf(2.0);
        private BigDecimal pricePerKm;
        private BigDecimal pricePerMinute;
        private BigDecimal minimumFare;

        public BigDecimal getBaseFare() {
            return baseFare;
        }

        public void setBaseFare(BigDecimal baseFare) {
            this.baseFare = baseFare;
        }

        public BigDecimal getIncludedDistanceKm() {
            return includedDistanceKm;
        }

        public void setIncludedDistanceKm(BigDecimal includedDistanceKm) {
            this.includedDistanceKm = includedDistanceKm;
        }

        public BigDecimal getPricePerKm() {
            return pricePerKm;
        }

        public void setPricePerKm(BigDecimal pricePerKm) {
            this.pricePerKm = pricePerKm;
        }

        public BigDecimal getPricePerMinute() {
            return pricePerMinute;
        }

        public void setPricePerMinute(BigDecimal pricePerMinute) {
            this.pricePerMinute = pricePerMinute;
        }

        public BigDecimal getMinimumFare() {
            return minimumFare;
        }

        public void setMinimumFare(BigDecimal minimumFare) {
            this.minimumFare = minimumFare;
        }
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public String getDefaultRuleVersion() {
        return defaultRuleVersion;
    }

    public void setDefaultRuleVersion(String defaultRuleVersion) {
        this.defaultRuleVersion = defaultRuleVersion;
    }

    public Map<VehicleType, PolicyProperties> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<VehicleType, PolicyProperties> policies) {
        this.policies = policies;
    }

    public Optional<PricingPolicy> getPolicy(ServiceType serviceType, VehicleType vehicleType) {
        PolicyProperties prop = policies.get(vehicleType);
        if (prop == null || prop.getBaseFare() == null) {
            return Optional.empty();
        }
        return Optional.of(new PricingPolicy(
                "RULE-DEV-" + vehicleType.name(),
                defaultRuleVersion,
                serviceType,
                vehicleType,
                prop.getBaseFare(),
                prop.getIncludedDistanceKm() != null ? prop.getIncludedDistanceKm() : BigDecimal.valueOf(2.0),
                prop.getPricePerKm(),
                prop.getPricePerMinute(),
                prop.getMinimumFare() != null ? prop.getMinimumFare() : prop.getBaseFare(),
                defaultCurrency
        ));
    }
}
