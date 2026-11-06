package com.rhl.dispatch.configs;

import com.rhl.dispatch.services.CandidateRankingPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatchingConfig {

    @Bean
    @ConditionalOnMissingBean(CandidateRankingPolicy.class)
    public CandidateRankingPolicy candidateRankingPolicy() {
        return new CandidateRankingPolicy.DefaultDistanceAndEtaRankingPolicy();
    }
}
