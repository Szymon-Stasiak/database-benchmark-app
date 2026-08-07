package com.dbagnets.backend.benchmark.setup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.benchmark.setup.port.ContainerManagementPort;
import com.dbagnets.backend.domain.BenchmarkStatus;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.domain.DatabaseType;
import com.dbagnets.backend.engine.driver.support.ConnectionCacheRegistry;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkDatabaseRepository;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.entity.User;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BenchmarkOperationsServiceTest {

    @Mock BenchmarkRepository benchmarkRepository;

    @Mock BenchmarkDatabaseRepository databaseRepository;

    @Mock ContainerManagementPort containerManager;

    @Mock BenchmarkDeploymentService deployment;

    @Mock DataSizeProbe dataSizeProbe;

    @Mock EntityIdRegistry entityIdRegistry;

    @Mock ConnectionCacheRegistry connectionCacheRegistry;

    @InjectMocks BenchmarkOperationsService operations;

    User user;

    @BeforeEach
    void setUp() {
        user = User.createFromJwtClaims("sub-1", "user@example.com", "User", null);
    }

    @Test
    void stopDatabase_stopsContainerAndUpdatesStatus() {
        BenchmarkDatabase db = database(DatabaseStatus.RUNNING);
        db.setContainerId("container-1");
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        operations.stopDatabase("bench-1", db.getId());

        verify(containerManager).stopContainer("container-1");
        verify(deployment).updateDatabaseStatus(db.getId(), DatabaseStatus.STOPPED);
        verify(deployment).finalizeBenchmark("bench-1");
    }

    @Test
    void stopDatabase_noOpWhenAlreadyStopped() {
        BenchmarkDatabase db = database(DatabaseStatus.STOPPED);
        db.setContainerId("container-1");
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        operations.stopDatabase("bench-1", db.getId());

        verify(containerManager, never()).stopContainer(any());
    }

    @Test
    void restartDatabase_restartsContainerAndSetsRunning() {
        BenchmarkDatabase db = database(DatabaseStatus.STOPPED);
        db.setContainerId("container-1");
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        operations.restartDatabase("bench-1", db.getId());

        verify(containerManager).restartContainer("container-1");
        verify(deployment).updateDatabaseStatus(db.getId(), DatabaseStatus.RUNNING);
    }

    @Test
    void getDatabaseLogs_returnsMessageWhenNoContainer() {
        BenchmarkDatabase db = database(DatabaseStatus.PENDING);
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));

        assertThat(operations.getDatabaseLogs(db.getId(), 100)).isEqualTo("No container running");
    }

    @Test
    void getDatabaseLogs_delegatesToContainerManagerWhenContainerExists() {
        BenchmarkDatabase db = database(DatabaseStatus.RUNNING);
        db.setContainerId("container-1");
        when(databaseRepository.findById(db.getId())).thenReturn(Optional.of(db));
        when(containerManager.getContainerLogs("container-1", 100)).thenReturn("log output");

        assertThat(operations.getDatabaseLogs(db.getId(), 100)).isEqualTo("log output");
    }

    @Test
    void redeployBenchmark_throwsWhenNoDatabasesAvailable() {
        Benchmark benchmark = new Benchmark("Topic", user, 3);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        assertThatThrownBy(() -> operations.redeployBenchmark("bench-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No databases available");
    }

    @Test
    void redeployBenchmark_reassignsRedeployableDatabases() {
        Benchmark benchmark = benchmarkWithId("bench-1");
        BenchmarkDatabase db = database(DatabaseStatus.FAILED);
        db.setScript("SELECT 1");
        db.setErrorMessage("old error");
        benchmark.addDatabase(db);
        when(benchmarkRepository.findById("bench-1")).thenReturn(Optional.of(benchmark));

        operations.redeployBenchmark("bench-1");

        assertThat(db.getStatus()).isEqualTo(DatabaseStatus.SCRIPT_READY);
        assertThat(db.getErrorMessage()).isNull();
        verify(deployment).cleanupContainer(db);
        verify(entityIdRegistry).evictAllForDatabase(db.getId());
        verify(deployment).updateBenchmarkStatus("bench-1", BenchmarkStatus.STARTING_CONTAINERS);
        verify(deployment).redeployAsync("bench-1");
    }

    @Test
    void hardResetBenchmark_wipesContainerStateAndTriggersRedeploy() {
        Benchmark benchmark = benchmarkWithId("bench-1abcdef0");
        BenchmarkDatabase db = database(DatabaseStatus.RUNNING);
        db.setContainerId("container-1");
        db.setHostPort(5555);
        db.setBaselineSizeBytes(1000L);
        benchmark.addDatabase(db);
        when(benchmarkRepository.findById("bench-1abcdef0")).thenReturn(Optional.of(benchmark));

        operations.hardResetBenchmark("bench-1abcdef0");

        verify(containerManager).stopContainer("container-1");
        verify(containerManager).hardRemoveContainer("container-1");
        assertThat(db.getContainerId()).isNull();
        assertThat(db.getHostPort()).isNull();
        assertThat(db.getStatus()).isEqualTo(DatabaseStatus.SCRIPT_READY);
        assertThat(db.getBaselineSizeBytes()).isNull();
        verify(connectionCacheRegistry).evictAll(db.getId());
        verify(deployment).redeployAsync("bench-1abcdef0");
    }

    private BenchmarkDatabase database(DatabaseStatus status) {
        BenchmarkDatabase db = new BenchmarkDatabase(DatabaseType.RELATIONAL, "postgresql", "16");
        db.setStatus(status);
        ReflectionTestUtils.setField(db, "id", "db-" + UUID.randomUUID());
        return db;
    }

    private Benchmark benchmarkWithId(String id) {
        Benchmark benchmark = new Benchmark("Topic", user, 3);
        ReflectionTestUtils.setField(benchmark, "id", id);
        return benchmark;
    }
}
