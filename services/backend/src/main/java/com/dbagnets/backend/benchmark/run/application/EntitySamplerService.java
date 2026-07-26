package com.dbagnets.backend.benchmark.run.application;

import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.engine.driver.pg.PgConnectionInfo;
import com.dbagnets.backend.engine.driver.pg.PgDataSourceCache;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class EntitySamplerService {

    private static final Logger LOG = LoggerFactory.getLogger(EntitySamplerService.class);

    @Value("${app.container-host}")
    private String hostAddress;

    private final BenchmarkRepository benchmarkRepository;
    private final LogicalSchemaLoader schemaLoader;
    private final PgDataSourceCache pgDataSourceCache;

    public String sampleParentIdWithChildren(String benchmarkId, String parentEntity) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        LogicalSchema schema = schemaLoader.parse(benchmark.getLogicalSchema());
        LogicalRelationship rel = schema.relationships().stream()
                .filter(r -> r.parentEntity().equalsIgnoreCase(parentEntity))
                .findFirst().orElse(null);
        if (rel == null) return null;
        BenchmarkDatabase pgDb = benchmark.getDatabases().stream()
                .filter(d -> "postgresql".equalsIgnoreCase(d.getDbName())
                        && d.getStatus() == DatabaseStatus.RUNNING
                        && d.getHostPort() != null)
                .findFirst().orElse(null);
        if (pgDb == null) return null;
        String fkCol = "\"" + rel.fkColumnInChild().toLowerCase() + "\"";
        String childTable = "\"" + rel.childEntity().toLowerCase() + "\"";
        String sql = "SELECT " + fkCol + " FROM " + childTable + " WHERE " + fkCol + " IS NOT NULL ORDER BY RANDOM() LIMIT 1";
        try {
            PgConnectionInfo info = PgConnectionInfo.defaultLocal(pgDb.getId(), hostAddress, pgDb.getHostPort());
            javax.sql.DataSource ds = pgDataSourceCache.get(info);
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            LOG.warn("sample-id-with-children fallback for {}: {}", parentEntity, e.getMessage());
        }
        return null;
    }
}