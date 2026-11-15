package com.ecommerceagent.automation.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The pilot default until a provider is chosen (O-11): records that a delivery happened, without its content. */
class LoggingMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailProvider.class);

    @Override
    public void send(MailMessage message) {
        log.info("E-mail delivery {} accepted by the logging provider", message.deliveryId());
    }
}
