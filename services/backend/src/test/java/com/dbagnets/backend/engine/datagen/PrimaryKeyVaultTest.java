package com.dbagnets.backend.engine.datagen;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrimaryKeyVaultTest {

    @Test
    void appendStoresUniqueValues() {
        PrimaryKeyVault vault = new PrimaryKeyVault();
        vault.append("Movie", "a");
        vault.append("Movie", "b");

        assertThat(vault.snapshot("Movie")).containsExactly("a", "b");
        assertThat(vault.size("Movie")).isEqualTo(2);
    }

    @Test
    void randomPkReturnsExistingValue() {
        PrimaryKeyVault vault = new PrimaryKeyVault();
        vault.append("Movie", "only");
        assertThat(vault.randomPk("Movie")).isEqualTo("only");
    }

    @Test
    void randomPkFailsWhenEmpty() {
        PrimaryKeyVault vault = new PrimaryKeyVault();
        assertThatThrownBy(() -> vault.randomPk("Movie"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No primary keys");
    }

    @Test
    void concurrentAppendsDoNotLoseValues() throws InterruptedException {
        PrimaryKeyVault vault = new PrimaryKeyVault();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        vault.append("E", UUID.randomUUID().toString());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        Set<String> unique = new HashSet<>(vault.snapshot("E"));
        assertThat(vault.size("E")).isEqualTo(threads * perThread);
        assertThat(unique).hasSize(threads * perThread);
    }
}
