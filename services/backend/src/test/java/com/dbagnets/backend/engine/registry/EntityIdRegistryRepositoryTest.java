package com.dbagnets.backend.engine.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityIdRegistryRepositoryTest {

    @Autowired EntityIdRegistryRepository repository;

    @Test
    void savesAndCountsByDatabaseAndEntity() {
        repository.save(new EntityIdRecord("b1", "db1", "Movie", "L1", "P1"));
        repository.save(new EntityIdRecord("b1", "db1", "Movie", "L2", "P2"));
        repository.save(new EntityIdRecord("b1", "db2", "Movie", "L1", "P1"));

        assertThat(repository.countByDatabaseIdAndEntityName("db1", "Movie")).isEqualTo(2);
        assertThat(repository.countByBenchmarkIdAndEntityName("b1", "Movie")).isEqualTo(3);
    }

    @Test
    void distinctLogicalIdsCollapsesAcrossDatabases() {
        repository.save(new EntityIdRecord("b1", "db1", "Review", "L1", "P1"));
        repository.save(new EntityIdRecord("b1", "db2", "Review", "L1", "P1"));
        repository.save(new EntityIdRecord("b1", "db1", "Review", "L2", "P2"));

        List<String> ids = repository.distinctLogicalIds("b1", "Review");
        assertThat(ids).containsExactlyInAnyOrder("L1", "L2");
    }

    @Test
    void deleteByLogicalIdsRemovesRecords() {
        repository.save(new EntityIdRecord("b1", "db1", "Comment", "L1", "P1"));
        repository.save(new EntityIdRecord("b1", "db1", "Comment", "L2", "P2"));
        repository.save(new EntityIdRecord("b1", "db1", "Comment", "L3", "P3"));

        int removed =
                repository.deleteByDatabaseIdAndEntityNameAndLogicalIdIn(
                        "db1", "Comment", List.of("L1", "L3"));

        assertThat(removed).isEqualTo(2);
        assertThat(repository.countByDatabaseIdAndEntityName("db1", "Comment")).isEqualTo(1);
    }
}
