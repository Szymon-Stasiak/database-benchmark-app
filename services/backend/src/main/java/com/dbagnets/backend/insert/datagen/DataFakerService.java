package com.dbagnets.backend.insert.datagen;

import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import net.datafaker.Faker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Generates a typed fake value for a single logical attribute.
 *
 * <p>This class is a thin facade: it enforces null-probability rules and the lookup order
 * (name first, then type), but the actual provider mapping lives in {@link FakerProviderRegistry}
 * to keep this class small and the mapping itself unit-testable in isolation.
 *
 * <p>Generation runs once per insert run on a single thread before parallel dispatch, so the
 * long-lived {@link Faker} instance is safe.
 */
@Component
public class DataFakerService {

    private static final double NULL_PROBABILITY = 0.10;

    private final Faker faker;
    private final Random random;
    private final FakerProviderRegistry registry;

    @Autowired
    public DataFakerService(FakerProviderRegistry registry) {
        this(new Faker(), new Random(), registry);
    }

    /** Test-only constructor preserved for backwards compatibility with the existing fixture seed. */
    DataFakerService(Faker faker, Random random) {
        this(faker, random, new FakerProviderRegistry(random));
    }

    DataFakerService(Faker faker, Random random, FakerProviderRegistry registry) {
        this.faker = faker;
        this.random = random;
        this.registry = registry;
    }

    public Object generate(LogicalAttribute attribute) {
        AttributeConstraints constraints = attribute.constraintsOrDefault();
        if (mayBeNull(constraints) && random.nextDouble() < NULL_PROBABILITY) {
            return null;
        }
        Object byName = registry.generateByName(faker, attribute.name());
        if (byName != null) {
            return byName;
        }
        Object byType = registry.generateByType(faker, attribute.dataType());
        if (byType != null) {
            return byType;
        }
        return faker.lorem().word();
    }

    private boolean mayBeNull(AttributeConstraints c) {
        return c.isNullable() && !c.isPrimaryKey() && !c.isUnique();
    }
}
