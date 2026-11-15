package com.ecommerceagent.automation.sync;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface HistoryImportActivities {

    /** One history chunk; returns the step outcome ({@code IMPORTED} when more remain). */
    String importNext(String connectionId);
}
