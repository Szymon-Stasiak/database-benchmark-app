package com.dbagnets.backend.engine.datagen;

import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import net.datafaker.Faker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public final class FakerCatalog {

    private static final double NULL_PROBABILITY = 0.10;

    private final Faker faker;

    public FakerCatalog() {
        this(new Faker());
    }

    public FakerCatalog(Faker faker) {
        this.faker = faker;
    }

    public Object generate(LogicalAttribute attr) {
        if (!attr.isPrimaryKey() && attr.isNullable() && ThreadLocalRandom.current().nextDouble() < NULL_PROBABILITY) {
            return null;
        }
        if (isTextLike(attr.dataType())) {
            Function<LogicalAttribute, Object> byName = matchByName(attr.name());
            if (byName != null) {
                return byName.apply(attr);
            }
        }
        return generateByType(attr);
    }

    private boolean isTextLike(LogicalDataType type) {
        return type == LogicalDataType.STRING || type == LogicalDataType.TEXT || type == LogicalDataType.JSON;
    }

    private Function<LogicalAttribute, Object> matchByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_id") && lower.equals("id")) return a -> UUID.randomUUID().toString();
        if (lower.contains("email")) return a -> faker.internet().emailAddress();
        if (lower.contains("phone")) return a -> faker.phoneNumber().phoneNumber();
        if (lower.contains("full_name") || lower.equals("name")) return a -> faker.name().fullName();
        if (lower.contains("first_name")) return a -> faker.name().firstName();
        if (lower.contains("last_name")) return a -> faker.name().lastName();
        if (lower.contains("username")) return a -> faker.name().username();
        if (lower.contains("password")) return a -> faker.internet().password();
        if (lower.contains("url") || lower.contains("uri")) return a -> faker.internet().url();
        if (lower.contains("avatar") || lower.contains("image")) return a -> faker.internet().image();
        if (lower.contains("title")) return a -> faker.book().title();
        if (lower.contains("biography") || lower.contains("bio") || lower.contains("synopsis") || lower.contains("description") || lower.contains("body"))
            return a -> faker.lorem().paragraph();
        if (lower.contains("nationality") || lower.contains("country")) return a -> faker.country().name();
        if (lower.contains("language")) return a -> faker.nation().language();
        if (lower.contains("city")) return a -> faker.address().city();
        if (lower.contains("address")) return a -> faker.address().fullAddress();
        if (lower.contains("slug")) return a -> faker.internet().slug();
        if (lower.contains("tags")) return a -> "[\"" + faker.lorem().word() + "\",\"" + faker.lorem().word() + "\"]";
        return null;
    }

    private Object generateByType(LogicalAttribute attr) {
        return switch (attr.dataType()) {
            case UUID -> UUID.randomUUID().toString();
            case STRING -> faker.lorem().sentence(3);
            case TEXT -> faker.lorem().paragraph();
            case INTEGER -> ThreadLocalRandom.current().nextInt(0, 100_000);
            case BIGINT -> ThreadLocalRandom.current().nextLong(0L, 10_000_000_000L);
            case FLOAT, DOUBLE -> ThreadLocalRandom.current().nextDouble(0.0, 1_000.0);
            case DECIMAL -> generateDecimal(attr);
            case BOOLEAN -> ThreadLocalRandom.current().nextBoolean();
            case DATE -> LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(0, 365 * 50));
            case TIMESTAMP -> Instant.now().minus(ThreadLocalRandom.current().nextLong(0, 365L * 24), ChronoUnit.HOURS);
            case JSON -> "{\"key\":\"" + faker.lorem().word() + "\",\"n\":" + ThreadLocalRandom.current().nextInt(100) + "}";
            case ENUM -> pickEnum(attr);
            case VECTOR -> generateVector(attr);
        };
    }

    private BigDecimal generateDecimal(LogicalAttribute attr) {
        int precision = attr.precision() == null ? 10 : attr.precision();
        int scale = attr.scale() == null ? 2 : attr.scale();
        double value = ThreadLocalRandom.current().nextDouble(0.0, Math.pow(10, Math.max(0, precision - scale)));
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private String pickEnum(LogicalAttribute attr) {
        if (attr.enumValues() == null || attr.enumValues().isEmpty()) {
            return faker.lorem().word();
        }
        int idx = ThreadLocalRandom.current().nextInt(attr.enumValues().size());
        return attr.enumValues().get(idx);
    }

    private float[] generateVector(LogicalAttribute attr) {
        int dim = attr.vectorDimensions() == null ? 128 : attr.vectorDimensions();
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) ThreadLocalRandom.current().nextGaussian();
        }
        return v;
    }

    public Instant fakerEpochSeed() {
        return Instant.ofEpochSecond(0).atZone(ZoneOffset.UTC).toInstant();
    }
}
