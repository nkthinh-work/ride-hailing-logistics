package com.rhl.pricing.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PricingRuleConflictException extends RuntimeException {
    public PricingRuleConflictException(String message) {
        super(message);
    }
}
