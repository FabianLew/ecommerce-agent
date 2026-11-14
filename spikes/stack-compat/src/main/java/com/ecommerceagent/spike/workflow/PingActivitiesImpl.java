package com.ecommerceagent.spike.workflow;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = PingWorkflowImpl.TASK_QUEUE)
public class PingActivitiesImpl implements PingActivities {

    @Override
    public String greet(String name) {
        return "pong:" + name;
    }
}
