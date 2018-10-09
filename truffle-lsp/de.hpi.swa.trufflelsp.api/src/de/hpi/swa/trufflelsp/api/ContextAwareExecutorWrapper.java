package de.hpi.swa.trufflelsp.api;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public interface ContextAwareExecutorWrapper {

    public <T> Future<T> executeWithDefaultContext(Callable<T> taskWithResult);

    default <T> Future<T> executeWithNestedContext(Callable<T> taskWithResult) {
        return executeWithNestedContext(taskWithResult, false);
    }

    public <T> Future<T> executeWithNestedContext(Callable<T> taskWithResult, boolean cached);

    public void resetCachedContext();

    public void shutdown();

}
