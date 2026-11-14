package com.ecommerceagent.automation.temporal;

import java.util.function.Supplier;

import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.InvalidIntegrationRequestException;
import com.ecommerceagent.integration.WooImportException;
import io.temporal.failure.ApplicationFailure;

/** Maps store-side errors to Temporal failures: the code becomes the failure type, permanent ones are not retried. */
public final class ActivityErrors {

    private ActivityErrors() {
    }

    public static <T> T guarded(Supplier<T> step) {
        try {
            return step.get();
        } catch (WooImportException e) {
            if (e.permanent()) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.error().name());
            }
            throw ApplicationFailure.newFailure(e.getMessage(), e.error().name());
        } catch (InvalidIntegrationRequestException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.code());
        } catch (IntegrationNotFoundException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "NOT_FOUND");
        }
    }
}
