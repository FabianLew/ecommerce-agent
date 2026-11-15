package io.temporal.worker;

/**
 * Test-only access to the worker's workflow cache, to simulate a worker restart: after {@link #forgetAll} every
 * open workflow is rebuilt from its history on the next task, as on a fresh worker (IG-24).
 */
public final class WorkerCrash {

    private WorkerCrash() {
    }

    public static void forgetAll(WorkerFactory factory) {
        factory.getCache().invalidateAll();
    }
}
