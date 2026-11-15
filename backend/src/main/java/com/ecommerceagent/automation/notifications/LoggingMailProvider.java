package com.ecommerceagent.automation.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local and pilot default until a provider is chosen (O-11): records that a delivery happened, without its
 * content. It has no lookup, no idempotency and no events, so an uncertain delivery is never resent.
 */
class LoggingMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailProvider.class);

    @Override
    public SendResult send(MailMessage message) {
        log.info("E-mail delivery {} accepted by the logging provider", message.deliveryId());
        return new Accepted("logged:" + message.deliveryId());
    }
}
