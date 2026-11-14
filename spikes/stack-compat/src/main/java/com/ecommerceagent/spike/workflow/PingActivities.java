package com.ecommerceagent.spike.workflow;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PingActivities {

    String greet(String name);
}
