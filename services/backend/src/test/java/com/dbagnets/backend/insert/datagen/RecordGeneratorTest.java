package com.dbagnets.backend.insert.datagen;

import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RecordGeneratorTest {

    private final RecordGenerator generator = new RecordGenerator(new DataFakerService(new Faker(new Random(7)), new Random(7)));

    @Test
    void generatesExactCountOfRecords() {
        LogicalEntity entity = userEntity();
        List<GeneratedRecord> records = generator.generate(entity, 25);
        assertEquals(25, records.size());
    }

    @Test
    void zeroOrNegativeCountReturnsEmpty() {
        assertTrue(generator.generate(userEntity(), 0).isEmpty());
        assertTrue(generator.generate(userEntity(), -5).isEmpty());
    }

    @Test
    void recordHasOneValuePerAttribute() {
        LogicalEntity entity = userEntity();
        GeneratedRecord r = generator.generate(entity, 1).get(0);
        assertEquals(entity.attributes().size(), r.size());
        for (LogicalAttribute a : entity.attributes()) {
            assertTrue(r.values().containsKey(a.name()));
        }
    }

    @Test
    void entityWithoutAttributesThrows() {
        LogicalEntity empty = new LogicalEntity("Empty", "no attrs", List.of());
        assertThrows(IllegalArgumentException.class, () -> generator.generate(empty, 1));
    }

    private LogicalEntity userEntity() {
        return new LogicalEntity("User", "test", List.of(
            new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null),
            new LogicalAttribute("email", "varchar", new AttributeConstraints(false, true, false, false), null),
            new LogicalAttribute("age", "int", new AttributeConstraints(false, false, true, false), null)
        ));
    }
}
