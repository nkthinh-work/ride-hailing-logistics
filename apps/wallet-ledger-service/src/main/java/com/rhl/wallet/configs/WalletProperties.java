package com.rhl.wallet.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "wallet")
public class WalletProperties {

    private String defaultCurrency = "VND";
    private Commission commission = new Commission();
    private Settlement settlement = new Settlement();

    public static class Commission {
        private String policyStatus = "COMMISSION_POLICY_PENDING";

        public String getPolicyStatus() {
            return policyStatus;
        }

        public void setPolicyStatus(String policyStatus) {
            this.policyStatus = policyStatus;
        }
    }

    public static class Settlement {
        private String policyStatus = "SETTLEMENT_POLICY_PENDING";

        public String getPolicyStatus() {
            return policyStatus;
        }

        public void setPolicyStatus(String policyStatus) {
            this.policyStatus = policyStatus;
        }
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Commission getCommission() {
        return commission;
    }

    public void setCommission(Commission commission) {
        this.commission = commission;
    }

    public Settlement getSettlement() {
        return settlement;
    }

    public void setSettlement(Settlement settlement) {
        this.settlement = settlement;
    }
}
