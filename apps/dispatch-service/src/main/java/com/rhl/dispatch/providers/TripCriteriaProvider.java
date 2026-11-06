package com.rhl.dispatch.providers;

import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import java.util.UUID;

public interface TripCriteriaProvider {
    TripMatchingCriteriaSnapshot getTripMatchingCriteria(UUID tripId);
}
