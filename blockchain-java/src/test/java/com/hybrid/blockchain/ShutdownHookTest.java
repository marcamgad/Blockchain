package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
public class ShutdownHookTest {

    @Test
    @DisplayName("Lifecycle: Ensure scheduled executors can be cleanly shut down")
    public void testExecutorShutdownBehavior() throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        Runnable task = () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        scheduler.submit(task);
        
        // Emulate App.java shutdown hook logic
        scheduler.shutdown();
        boolean terminated = scheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        
        assertThat(scheduler.isShutdown()).isTrue();
        assertThat(terminated).isTrue();
    }
}
