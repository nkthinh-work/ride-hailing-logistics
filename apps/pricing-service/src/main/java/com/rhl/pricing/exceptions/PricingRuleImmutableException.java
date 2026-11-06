package com.rhl.pricing.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PricingRuleImmutableException extends RuntimeException {
    public PricingRuleImmutableException(String message) {
        super(message);
    }
}
