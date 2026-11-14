package com.ecommerceagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

// Default web environment on purpose: only the worker profile may switch the HTTP server off.
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class WorkerProfileIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    WorkerFactory workers;

    @Test
    void workerProfileStartsWithoutHttpServer() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
    }

    @Test
    void workerProfileStartsTemporalWorkers() {
        assertThat(workers.isStarted()).isTrue();
        assertThat(workers.getWorker(TaskQueues.MAIN)).isNotNull();
    }
}
