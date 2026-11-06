package com.rhl.pricing.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidPricingRuleException extends RuntimeException {
    public InvalidPricingRuleException(String message) {
        super(message);
    }
}
