package com.dbagnets.backend.benchmark.setup.application;

import com.dbagnets.backend.benchmark.setup.api.dto.BenchmarkResponse;
import com.dbagnets.backend.benchmark.setup.internal.BenchmarkBundleService;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.domain.DatabaseType;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkDatabaseRepository;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BenchmarkLifecycleServiceTest {

    @Mock BenchmarkRepository benchmarkRepository;
    @Mock BenchmarkDatabaseRepository databaseRepository;
    @Mock BenchmarkBundleService bundleService;
    @Mock BenchmarkDeploymentService deployment;
    @Mock EntityIdRegistry entityIdRegistry;

    @InjectMocks BenchmarkLifecycleService lifecycle;

    User user;

    @BeforeEach
    void setUp() {
        user = User.createFromJwtClaims("sub-1", "user@example.com", "User", null);
    }

    @Test
    void getBenchmark_returnsResponseWhenFound() {
        Benchmark benchmark = new Benchmark("Topic", user, 3);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        BenchmarkResponse response = lifecycle.getBenchmark("bench-1");

        assertThat(response.topic()).isEqualTo("Topic");
    }

    @Test
    void getBenchmark_throwsWhenNotFound() {
        when(benchmarkRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycle.getBenchmark("missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void listBenchmarks_returnsUserScopedList() {
        Benchmark b1 = new Benchmark("A", user, 3);
        Benchmark b2 = new Benchmark("B", user, 3);
        when(benchmarkRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(b1, b2));

        List<BenchmarkResponse> result = lifecycle.listBenchmarks(user);

        assertThat(result).extracting(BenchmarkResponse::topic).containsExactly("A", "B");
    }

    @Test
    void deleteBenchmark_cleansContainersAndRegistryThenDeletes() {
        Benchmark benchmark = benchmarkWithId("bench-1");
        BenchmarkDatabase db = database();
        db.setContainerId("container-1");
        benchmark.addDatabase(db);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        lifecycle.deleteBenchmark("bench-1");

        verify(deployment).cleanupContainer(db);
        verify(entityIdRegistry).evictAllForBenchmark("bench-1");
        verify(benchmarkRepository).delete(benchmark);
    }

    @Test
    void deleteBenchmark_throwsWhenNotFound() {
        when(benchmarkRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycle.deleteBenchmark("missing"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deleteDatabase_deletesBenchmarkWhenLastDatabaseRemoved() {
        Benchmark benchmark = benchmarkWithId("bench-1");
        BenchmarkDatabase db = database();
        benchmark.addDatabase(db);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        lifecycle.deleteDatabase("bench-1", db.getId());

        verify(benchmarkRepository).delete(benchmark);
        verify(benchmarkRepository, never()).save(any(Benchmark.class));
    }

    @Test
    void deleteDatabase_savesBenchmarkWhenOtherDatabasesRemain() {
        Benchmark benchmark = benchmarkWithId("bench-1");
        BenchmarkDatabase db1 = database();
        BenchmarkDatabase db2 = database();
        benchmark.addDatabase(db1);
        benchmark.addDatabase(db2);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        lifecycle.deleteDatabase("bench-1", db1.getId());

        verify(benchmarkRepository, never()).delete(benchmark);
        verify(benchmarkRepository, atLeastOnce()).save(benchmark);
        assertThat(benchmark.getDatabases()).hasSize(1);
        assertThat(benchmark.getDatabases().get(0)).isEqualTo(db2);
        verify(deployment).finalizeBenchmark("bench-1");
    }

    @Test
    void downloadScript_returnsBytesWhenScriptPresent() {
        BenchmarkDatabase db = database();
        db.setScript("CREATE TABLE t(id INT);");
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        byte[] bytes = lifecycle.downloadScript(db.getId());

        assertThat(new String(bytes)).isEqualTo("CREATE TABLE t(id INT);");
    }

    @Test
    void downloadScript_throwsWhenScriptMissing() {
        BenchmarkDatabase db = database();
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThatThrownBy(() -> lifecycle.downloadScript(db.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void getScriptPreview_truncatesLongScript() {
        BenchmarkDatabase db = database();
        db.setScript("x".repeat(2000));
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThat(lifecycle.getScriptPreview(db.getId())).hasSize(1000);
    }

    @Test
    void getScriptPreview_returnsNullWhenNoScript() {
        BenchmarkDatabase db = database();
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThat(lifecycle.getScriptPreview(db.getId())).isNull();
    }

    @Test
    void getContainerId_returnsOptionalOfContainerId() {
        BenchmarkDatabase db = database();
        db.setContainerId("container-1");
        db.setStatus(DatabaseStatus.RUNNING);
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThat(lifecycle.getContainerId(db.getId())).contains("container-1");
    }

    @Test
    void getContainerId_returnsEmptyWhenNoContainer() {
        BenchmarkDatabase db = database();
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThat(lifecycle.getContainerId(db.getId())).isEmpty();
    }

    private BenchmarkDatabase database() {
        BenchmarkDatabase db = new BenchmarkDatabase(DatabaseType.RELATIONAL, "postgresql", "16");
        ReflectionTestUtils.setField(db, "id", "db-" + UUID.randomUUID());
        return db;
    }

    private Benchmark benchmarkWithId(String id) {
        Benchmark benchmark = new Benchmark("Topic", user, 3);
        ReflectionTestUtils.setField(benchmark, "id", id);
        return benchmark;
    }
}
