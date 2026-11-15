package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import com.ecommerceagent.automation.notifications.AlertSettings;
import com.ecommerceagent.automation.notifications.MailProvider;
import com.ecommerceagent.automation.notifications.NotificationDeliveryException;
import com.ecommerceagent.automation.notifications.NotificationEvent;
import com.ecommerceagent.automation.notifications.NotificationKind;
import com.ecommerceagent.automation.notifications.OperatorNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Operator notifications (B-41, ADR-0021): stored once per key, e-mailed once per approver, retried without touching
 * the action (IG-23), read marks per person and store isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "operator.notifications.retry-interval=0s")
class NotificationApiTest extends DecisionScenario {

    static final UUID OPERATOR = UUID.fromString("b8300000-0000-4000-8000-0000000000c1");
    static final UUID ANALYST = UUID.fromString("b8300000-0000-4000-8000-0000000000d1");

    @MockitoBean
    GuardianSignals signals;
    @MockitoBean
    MailProvider mail;
    @Autowired
    OperatorNotifier notifier;
    @Autowired
    AlertSettings alerts;

    @BeforeEach
    void people() {
        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m-1"));
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, ?, 'operator', 'op@shop.test'), "
                + "(?, ?, 'analyst', 'an@shop.test')").params(OPERATOR, ISSUER, ANALYST, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'DECISION_OPERATOR'), (?, ?, ?, 'ANALYST')
                """).params(ORG, store, OPERATOR, ORG, store, ANALYST).update();
        // ADR-0026 point 5: e-mails go to the owners and the deputy; the decision operator is the deputy here.
        jdbc.sql("""
                INSERT INTO store_alert_setting (store_id, organization_id, deputy_user_id, version, changed_by,
                                                 changed_at)
                VALUES (?, ?, ?, 1, ?, now())
                """).params(store, ORG, OPERATOR, OWNER).update();
    }

    NotificationEvent verified(String key) {
        return new NotificationEvent(key, NotificationKind.ACTION_VERIFIED, null,
                Map.of("adExternalId", "ad-9001", "adName", "Serum dedicated", "sku", "SERUM-30"), null, null,
                UUID.randomUUID());
    }


    @Test
    void aNotificationIsStoredOnceAndMailedOnceToEachApprover() {
        UUID first = notifier.notify(scope, verified("exec-1/VERIFIED"));
        UUID again = notifier.notify(scope, verified("exec-1/VERIFIED"));

        assertThat(again).isEqualTo(first);
        ArgumentCaptor<MailProvider.MailMessage> sent = ArgumentCaptor.forClass(MailProvider.MailMessage.class);
        verify(mail, times(2)).send(sent.capture());
        // The owner and the deputy, not the analyst.
        assertThat(sent.getAllValues()).extracting(MailProvider.MailMessage::to)
                .containsExactlyInAnyOrder("o@shop.test", "op@shop.test");
        assertThat(sent.getValue().subject()).isEqualTo("Paused and confirmed: Serum dedicated (ad-9001)");
        assertThat(sent.getValue().body()).contains("SERUM-30", "confirmed by reading it from Meta");
        assertThat(jdbc.sql("SELECT status FROM notification_delivery WHERE store_id = ?").param(store)
                .query(String.class).list()).containsOnly("SENT");
    }

    List<String> recipientsOf(String key) {
        UUID id = notifier.notify(scope, verified(key));
        return jdbc.sql("""
                        SELECT u.email FROM notification_delivery d JOIN app_user u ON u.id = d.recipient_user_id
                        WHERE d.notification_id = ?
                        """).param(id).query(String.class).list();
    }

    /** ADR-0026 point 5: owners and the deputy only; a deputy who lost the permission gets nothing. */
    @Test
    void eMailsGoToTheOwnersAndTheDeputyOnly() {
        assertThat(recipientsOf("r-1/VERIFIED")).containsExactlyInAnyOrder("o@shop.test", "op@shop.test");

        jdbc.sql("UPDATE store_alert_setting SET deputy_user_id = NULL WHERE store_id = ?").param(store).update();
        assertThat(recipientsOf("r-2/VERIFIED")).containsExactly("o@shop.test");

        jdbc.sql("UPDATE store_alert_setting SET deputy_user_id = ? WHERE store_id = ?").params(OPERATOR, store)
                .update();
        jdbc.sql("UPDATE store_membership SET role = 'ANALYST' WHERE user_id = ? AND store_id = ?")
                .params(OPERATOR, store).update();
        assertThat(recipientsOf("r-3/VERIFIED")).containsExactly("o@shop.test");
        assertThat(alerts.setting(scope).deputyActive()).isFalse();
        assertThat(alerts.setting(scope).deputyUserId()).isEqualTo(OPERATOR);
    }

    @Test
    void onlyAStoreManagerSetsTheDeputyFromTheVersionSeen() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}/alert-settings", store).with(user("operator")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"alert-setting-1\""))
                .andExpect(jsonPath("$.deputyUserId").value(OPERATOR.toString()))
                .andExpect(jsonPath("$.deputyActive").value(true))
                .andExpect(jsonPath("$.candidates[0].userId").value(OPERATOR.toString()))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.canChange").value(false));
        String clear = "{\"deputyUserId\":null}";
        mvc.perform(put("/api/v1/stores/{id}/alert-settings", store).with(user("operator"))
                .header("If-Match", "\"alert-setting-1\"").contentType(MediaType.APPLICATION_JSON).content(clear))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/stores/{id}/alert-settings", store).with(user("owner"))
                .contentType(MediaType.APPLICATION_JSON).content(clear))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put("/api/v1/stores/{id}/alert-settings", store).with(user("owner"))
                .header("If-Match", "\"alert-setting-0\"").contentType(MediaType.APPLICATION_JSON).content(clear))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(put("/api/v1/stores/{id}/alert-settings", store).with(user("owner"))
                .header("If-Match", "\"alert-setting-1\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deputyUserId\":\"" + ANALYST + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("DEPUTY_NOT_APPROVER"));
        mvc.perform(put("/api/v1/stores/{id}/alert-settings", store).with(user("owner"))
                .header("If-Match", "\"alert-setting-1\"").contentType(MediaType.APPLICATION_JSON).content(clear))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"alert-setting-2\""))
                .andExpect(jsonPath("$.deputyUserId").doesNotExist())
                .andExpect(jsonPath("$.changedBy").value(OWNER.toString()));
        assertThat(jdbc.sql("""
                        SELECT details ->> 'from' FROM audit_event
                        WHERE store_id = ? AND action = 'ALERT_DEPUTY_CHANGED'
                        """).param(store).query(String.class).list()).containsExactly(OPERATOR.toString());
    }

    List<String> statuses() {
        return jdbc.sql("SELECT status FROM notification_delivery WHERE store_id = ?").param(store)
                .query(String.class).list();
    }

    @Test
    void aRefusedCallFailsAndALaterSweepSendsItWithTheSameMessageId() {
        // IG-23: the channel fails; the notification stays, and only the e-mail is retried.
        when(mail.send(any())).thenReturn(new MailProvider.Refused("THROTTLED", true));
        assertThatThrownBy(() -> notifier.notify(scope, verified("exec-2/VERIFIED")))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThat(notifier.byKey(scope, "exec-2/VERIFIED")).isPresent();
        assertThat(statuses()).containsOnly("FAILED");

        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m-2"));
        assertThat(notifier.retryOpen(10)).isZero();
        assertThat(statuses()).containsOnly("SENT");
        // SENT is never resent: a second sweep sends nothing.
        notifier.retryOpen(10);
        ArgumentCaptor<MailProvider.MailMessage> sent = ArgumentCaptor.forClass(MailProvider.MailMessage.class);
        verify(mail, times(4)).send(sent.capture());
        // Every attempt for a delivery carries the same idempotency key and Message-ID.
        Map<String, List<String>> byDelivery = sent.getAllValues().stream().collect(Collectors.groupingBy(
                MailProvider.MailMessage::deliveryId, Collectors.mapping(MailProvider.MailMessage::messageId,
                        Collectors.toList())));
        assertThat(byDelivery).hasSize(2).allSatisfy((delivery, ids) -> assertThat(ids)
                .containsExactly("<" + delivery + "@notifications.localhost>",
                        "<" + delivery + "@notifications.localhost>"));
        assertThat(jdbc.sql("SELECT DISTINCT provider_message_id FROM notification_delivery WHERE store_id = ?")
                .param(store).query(String.class).list()).containsExactly("m-2");
    }

    @Test
    void anUncertainCallIsReconciledByLookupWithoutASecondCall() {
        when(mail.capabilities()).thenReturn(new MailProvider.Capabilities(false, true, false));
        when(mail.send(any())).thenReturn(new MailProvider.Uncertain("TIMEOUT"));
        assertThatThrownBy(() -> notifier.notify(scope, verified("exec-5/VERIFIED")))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThat(statuses()).containsOnly("UNKNOWN");

        when(mail.lookup(any())).thenReturn(new MailProvider.Lookup(MailProvider.LookupStatus.FOUND, "m-5"));
        assertThat(notifier.retryOpen(10)).isZero();
        assertThat(statuses()).containsOnly("SENT");
        verify(mail, times(2)).send(any());
        verify(mail, times(2)).lookup(any());
    }

    @Test
    void aLookupThatDoesNotFindTheMessageAllowsOneResend() {
        when(mail.capabilities()).thenReturn(new MailProvider.Capabilities(false, true, false));
        when(mail.send(any())).thenThrow(new IllegalStateException("connection reset"));
        assertThatThrownBy(() -> notifier.notify(scope, verified("exec-6/VERIFIED")))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThat(statuses()).containsOnly("UNKNOWN");

        when(mail.lookup(any())).thenReturn(new MailProvider.Lookup(MailProvider.LookupStatus.NOT_FOUND, null));
        org.mockito.Mockito.reset(mail);
        when(mail.capabilities()).thenReturn(new MailProvider.Capabilities(false, true, false));
        when(mail.lookup(any())).thenReturn(new MailProvider.Lookup(MailProvider.LookupStatus.NOT_FOUND, null));
        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m-6"));
        assertThat(notifier.retryOpen(10)).isZero();
        assertThat(statuses()).containsOnly("SENT");
        verify(mail, times(2)).send(any());
    }

    @Test
    void withIdempotencyKeysAnUncertainDeliveryIsResentAndWithNeitherCapabilityItIsNot() {
        when(mail.capabilities()).thenReturn(new MailProvider.Capabilities(true, false, false));
        when(mail.send(any())).thenReturn(new MailProvider.Uncertain("HTTP_503"));
        assertThatThrownBy(() -> notifier.notify(scope, verified("exec-7/VERIFIED")))
                .isInstanceOf(NotificationDeliveryException.class);
        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m-7"));
        notifier.retryOpen(10);
        assertThat(statuses()).containsOnly("SENT");
        verify(mail, times(4)).send(any());

        // Neither lookup nor idempotency: an uncertain delivery is never resent, and ends EXHAUSTED.
        org.mockito.Mockito.reset(mail);
        when(mail.send(any())).thenReturn(new MailProvider.Uncertain("TIMEOUT"));
        assertThatThrownBy(() -> notifier.notify(scope, verified("exec-8/VERIFIED")))
                .isInstanceOf(NotificationDeliveryException.class);
        for (int sweep = 0; sweep < 6; sweep++) {
            notifier.retryOpen(10);
        }
        verify(mail, times(2)).send(any());
        assertThat(jdbc.sql("""
                        SELECT d.status FROM notification_delivery d JOIN operator_notification n
                          ON n.id = d.notification_id
                        WHERE n.store_id = ? AND n.dedup_key = 'exec-8/VERIFIED'
                        """).param(store).query(String.class).list()).containsOnly("EXHAUSTED");
    }

    @Test
    void anExpiredClaimIsUncertainAndProviderEventsMoveTheDelivery() {
        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m-9"));
        notifier.notify(scope, verified("exec-9/VERIFIED"));
        List<UUID> deliveries = jdbc.sql("SELECT id FROM notification_delivery WHERE store_id = ? ORDER BY id")
                .param(store).query(UUID.class).list();
        // A crash while sending leaves an expired claim: it may have been taken, so it is not sent again.
        jdbc.sql("UPDATE notification_delivery SET status = 'SENDING', claimed_until = now() - interval '1 minute' "
                + "WHERE id = ?").param(deliveries.get(0)).update();
        notifier.retryOpen(10);
        assertThat(jdbc.sql("SELECT status FROM notification_delivery WHERE id = ?").param(deliveries.get(0))
                .query(String.class).single()).isEqualTo("UNKNOWN");
        verify(mail, times(2)).send(any());

        Instant now = clock.instant();
        assertThat(notifier.recordProviderEvent(scope, deliveries.get(0), OperatorNotifier.DeliveryEvent.DELIVERED,
                "evt-1", now)).isTrue();
        assertThat(notifier.recordProviderEvent(scope, deliveries.get(0), OperatorNotifier.DeliveryEvent.DELIVERED,
                "evt-1", now)).isFalse();
        assertThat(notifier.recordProviderEvent(scope, deliveries.get(1), OperatorNotifier.DeliveryEvent.BOUNCED,
                "evt-2", now)).isTrue();
        assertThat(jdbc.sql("SELECT status FROM notification_delivery WHERE store_id = ? ORDER BY id").param(store)
                .query(String.class).list()).containsExactly("DELIVERED", "BOUNCED");
        // A bounce is not retried, and another store's delivery cannot be touched.
        notifier.retryOpen(10);
        verify(mail, times(2)).send(any());
        var other = storeAccess.systemScope(com.ecommerceagent.automation.events.EventDispatch.SYNC_SERVICE, ORG,
                otherStore).orElseThrow();
        assertThat(notifier.recordProviderEvent(other, deliveries.get(1), OperatorNotifier.DeliveryEvent.DELIVERED,
                "evt-3", now)).isFalse();
    }

    @Test
    void theListShowsEachPersonsReadMarksAndStaysInTheStore() throws Exception {
        UUID id = notifier.notify(scope, verified("exec-3/VERIFIED"));
        notifier.notify(scope, new NotificationEvent("exec-4/UNKNOWN", NotificationKind.ACTION_UNKNOWN,
                "PAUSE_NOT_CONFIRMED", Map.of("adExternalId", "ad-9001"), null, null, UUID.randomUUID()));

        mvc.perform(get("/api/v1/stores/{id}/notifications", store).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("ACTION_UNKNOWN"))
                .andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].code").value("PAUSE_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.items[1].parameters.sku").value("SERUM-30"));
        mvc.perform(post("/api/v1/stores/{id}/notifications/{n}/read", store, id).with(user("owner")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/stores/{id}/notifications", store).with(user("owner")))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[1].read").value(true));
        // The mark is the owner's: the analyst still sees both unread.
        mvc.perform(get("/api/v1/stores/{id}/notifications?limit=1", store).with(user("analyst")))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));

        // Store B's owner neither sees nor marks store A's notifications.
        mvc.perform(get("/api/v1/stores/{id}/notifications", otherStore).with(user("outsider")))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/api/v1/stores/{id}/notifications/{n}/read", otherStore, id).with(user("outsider")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM operator_notification_read").query(Long.class).single())
                .isEqualTo(1);
    }
}
