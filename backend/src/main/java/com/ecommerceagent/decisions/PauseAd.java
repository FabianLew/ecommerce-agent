package com.ecommerceagent.decisions;

import java.util.Objects;
import java.util.UUID;

/** Pause one Meta ad; {@code adId} is our ad id, never the platform's. */
public record PauseAd(UUID adId) implements ActionSpec {

    public static final String TYPE = "PAUSE_AD";
    public static final String TARGET_TYPE = "META_AD";
    public static final String DESIRED_STATUS = "PAUSED";

    public PauseAd {
        Objects.requireNonNull(adId, "adId");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
