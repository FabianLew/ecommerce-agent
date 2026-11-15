package com.ecommerceagent.automation.notifications;

import java.util.Map;

/**
 * The e-mail wording, in English until the person's language is stored (ADR-0021 point 2). Only parameters set by
 * the backend appear here; no model text, customer data or credentials.
 */
final class NotificationText {

    private NotificationText() {
    }

    static String subject(Notification notification) {
        String ad = ad(notification.parameters());
        return switch (notification.kind()) {
            case DECISION_NEEDED -> "Decision needed: pause " + ad + "?";
            case ACTION_VERIFIED -> "Paused and confirmed: " + ad;
            case ACTION_NOOP -> "Already paused: " + ad;
            case ACTION_UNKNOWN -> "Check needed: the pause of " + ad + " is not confirmed";
            case ACTION_FAILED -> "Not paused: " + ad;
            case ACTION_CONFLICT -> "Not paused, something changed: " + ad;
        };
    }

    static String body(Notification notification, String storeUrl) {
        Map<String, String> p = notification.parameters();
        String ad = ad(p);
        String product = p.getOrDefault("sku", "the product");
        String text = switch (notification.kind()) {
            case DECISION_NEEDED -> "The operator recommends pausing " + ad + " because stock of " + product
                    + " may run out. Nothing changes until a person approves it.";
            case ACTION_VERIFIED -> "The ad " + ad + " was paused and the pause was confirmed by reading it from Meta."
                    + " Stock of " + product + " keeps being watched. Spend may move to other ads in the ad set.";
            case ACTION_NOOP -> "The ad " + ad + " was already paused, so nothing was changed.";
            case ACTION_UNKNOWN -> "The pause of " + ad + " was sent, but Meta has not confirmed it (" + code(notification)
                    + "). Nothing will be sent again automatically. Check the ad in Ads Manager.";
            case ACTION_FAILED -> "The ad " + ad + " was not paused (" + code(notification) + ").";
            case ACTION_CONFLICT -> "The ad " + ad + " was not paused because it changed since the approval ("
                    + code(notification) + "). A new analysis decides again.";
        };
        return text + "\n\nDetails: " + storeUrl + "/decisions" + (notification.proposalId() == null ? ""
                : "/" + notification.proposalId()) + "\n";
    }

    private static String ad(Map<String, String> p) {
        String id = p.getOrDefault("adExternalId", "the ad");
        String name = p.get("adName");
        return name == null || name.isBlank() ? id : name + " (" + id + ")";
    }

    private static String code(Notification notification) {
        return notification.code() == null ? "no reason given" : notification.code();
    }
}
