package com.dbagnets.backend.insert.datagen;

import net.datafaker.Faker;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Routes attribute names and SQL-ish data types to {@link Faker} providers.
 *
 * <p>Datafaker ships with 800+ providers; the legacy mapping inside {@link DataFakerService} only
 * touched ~10 of them. This registry expands coverage to ~60 name patterns and ~20 type fallbacks
 * so generated rows look more like production data.
 *
 * <p>Order matters: the first matching {@link NamePattern} wins, so more specific patterns must
 * appear before more general ones (e.g. {@code first_name} before {@code name}).
 */
@Component
public class FakerProviderRegistry {

    private final List<NamePattern> namePatterns;
    private final List<TypePattern> typePatterns;
    private final Random random;

    public FakerProviderRegistry() {
        this(new Random());
    }

    FakerProviderRegistry(Random random) {
        this.random = random;
        this.namePatterns = buildNamePatterns();
        this.typePatterns = buildTypePatterns();
    }

    /** Returns a fake value for the attribute name, or {@code null} if no pattern matches. */
    public Object generateByName(Faker faker, String attributeName) {
        if (attributeName == null) return null;
        String name = attributeName.toLowerCase(Locale.ROOT);
        for (NamePattern pattern : namePatterns) {
            if (pattern.match.test(name)) {
                return pattern.producer.apply(faker);
            }
        }
        return null;
    }

    /** Returns a fake value for the SQL-ish data type, or {@code null} if no pattern matches. */
    public Object generateByType(Faker faker, String dataType) {
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        for (TypePattern pattern : typePatterns) {
            if (pattern.match.test(type)) {
                return pattern.producer.apply(faker, random);
            }
        }
        return null;
    }

    /* ====================================================================== */
    /* Name patterns — 60+ entries, evaluated top-to-bottom (first match wins) */
    /* ====================================================================== */

    private static List<NamePattern> buildNamePatterns() {
        List<NamePattern> p = new ArrayList<>(80);

        // --- People / identity (most specific first) -------------------------
        p.add(nameEquals("first_name", f -> f.name().firstName()));
        p.add(nameEquals("last_name", f -> f.name().lastName()));
        p.add(nameEquals("middle_name", f -> f.name().firstName()));
        p.add(nameEquals("full_name", f -> f.name().fullName()));
        p.add(nameEquals("display_name", f -> f.name().fullName()));
        p.add(nameContains("prefix", f -> f.name().prefix()));
        p.add(nameContains("suffix", f -> f.name().suffix()));
        p.add(nameContains("nickname", f -> f.name().username()));

        // --- Contact info ----------------------------------------------------
        p.add(nameContains("email", f -> f.internet().emailAddress()));
        p.add(nameContains("phone", f -> f.phoneNumber().phoneNumber()));
        p.add(nameContains("mobile", f -> f.phoneNumber().cellPhone()));
        p.add(nameContains("fax", f -> f.phoneNumber().phoneNumber()));

        // --- Internet / web --------------------------------------------------
        p.add(nameContains("username", f -> f.internet().username()));
        p.add(nameContains("login", f -> f.internet().username()));
        p.add(nameContains("password", f -> f.internet().password(8, 32)));
        p.add(nameContains("url", f -> f.internet().url()));
        p.add(nameContains("website", f -> f.internet().url()));
        p.add(nameContains("domain", f -> f.internet().domainName()));
        p.add(nameContains("ipv6", f -> f.internet().ipV6Address()));
        p.add(nameContains("ipv4", f -> f.internet().ipV4Address()));
        p.add(name(
            n -> n.contains("ip") && (n.endsWith("_ip") || n.startsWith("ip_") || n.equals("ip")),
            f -> f.internet().ipV4Address()));
        p.add(nameContains("mac_address", f -> f.internet().macAddress()));
        p.add(nameContains("user_agent", f -> f.internet().userAgent().toString()));
        p.add(nameContains("slug", f -> f.internet().slug()));

        // --- Address ---------------------------------------------------------
        p.add(nameContains("street", f -> f.address().streetAddress()));
        p.add(nameContains("address", f -> f.address().fullAddress()));
        p.add(nameContains("zip", f -> f.address().zipCode()));
        p.add(nameContains("postcode", f -> f.address().zipCode()));
        p.add(nameContains("postal_code", f -> f.address().zipCode()));
        p.add(nameContains("city", f -> f.address().city()));
        p.add(nameContains("state", f -> f.address().state()));
        p.add(nameContains("province", f -> f.address().state()));
        p.add(nameContains("country", f -> f.address().country()));
        p.add(nameContains("latitude", f -> Double.parseDouble(f.address().latitude().replace(",", "."))));
        p.add(nameContains("longitude", f -> Double.parseDouble(f.address().longitude().replace(",", "."))));
        p.add(nameContains("timezone", f -> f.address().timeZone()));

        // --- Finance ---------------------------------------------------------
        p.add(nameContains("iban", f -> f.finance().iban()));
        p.add(nameContains("swift", f -> f.finance().bic()));
        p.add(nameContains("bic", f -> f.finance().bic()));
        p.add(nameContains("credit_card", f -> f.finance().creditCard()));
        p.add(nameContains("currency_code", f -> f.money().currencyCode()));
        p.add(nameContains("currency", f -> f.money().currency()));
        p.add(nameContains("price", f -> f.number().randomDouble(2, 1, 100_000)));
        p.add(nameContains("amount", f -> f.number().randomDouble(2, 1, 100_000)));
        p.add(nameContains("cost", f -> f.number().randomDouble(2, 1, 100_000)));
        p.add(nameContains("balance", f -> f.number().randomDouble(2, -10_000, 100_000)));
        p.add(nameContains("salary", f -> f.number().numberBetween(20_000L, 250_000L)));

        // --- Company / org ---------------------------------------------------
        p.add(nameContains("company", f -> f.company().name()));
        p.add(nameContains("industry", f -> f.company().industry()));
        p.add(nameContains("department", f -> f.commerce().department()));
        p.add(nameContains("job", f -> f.job().title()));
        p.add(nameContains("position", f -> f.job().position()));
        p.add(nameContains("role", f -> f.job().title()));

        // --- Commerce / products --------------------------------------------
        p.add(nameContains("product", f -> f.commerce().productName()));
        p.add(nameContains("sku", f -> f.code().asin()));
        p.add(nameContains("isbn", f -> f.code().isbn13()));
        p.add(nameContains("ean", f -> f.code().ean13()));
        p.add(nameContains("upc", f -> f.code().ean13()));
        p.add(nameContains("barcode", f -> f.code().ean13()));
        p.add(nameContains("brand", f -> f.commerce().brand()));
        p.add(nameContains("color", f -> f.color().hex()));
        p.add(nameContains("material", f -> f.commerce().material()));

        // --- Vehicle ---------------------------------------------------------
        p.add(nameContains("vin", f -> f.vehicle().vin()));
        p.add(nameContains("license_plate", f -> f.vehicle().licensePlate()));
        p.add(nameContains("vehicle_make", f -> f.vehicle().make()));
        p.add(nameContains("vehicle_model", f -> f.vehicle().model()));
        p.add(name(n -> n.equals("make"), f -> f.vehicle().make()));

        // --- Media / content -------------------------------------------------
        p.add(nameContains("title", f -> f.book().title()));
        p.add(nameContains("author", f -> f.book().author()));
        p.add(nameContains("publisher", f -> f.book().publisher()));
        p.add(nameContains("genre", f -> f.book().genre()));
        p.add(nameContains("description", f -> f.lorem().sentence(12)));
        p.add(nameContains("summary", f -> f.lorem().paragraph(2)));
        p.add(nameContains("content", f -> f.lorem().paragraph(3)));
        p.add(nameContains("body", f -> f.lorem().paragraph(3)));
        p.add(nameContains("comment", f -> f.lorem().sentence(10)));
        p.add(nameContains("note", f -> f.lorem().sentence(8)));
        p.add(nameContains("message", f -> f.lorem().sentence(10)));
        p.add(nameContains("tag", f -> f.lorem().word()));
        p.add(nameContains("label", f -> f.lorem().word()));

        // --- Food / drink ----------------------------------------------------
        p.add(nameContains("dish", f -> f.food().dish()));
        p.add(nameContains("ingredient", f -> f.food().ingredient()));
        p.add(nameContains("fruit", f -> f.food().fruit()));
        p.add(nameContains("vegetable", f -> f.food().vegetable()));
        p.add(nameContains("beer", f -> f.beer().name()));

        // --- Linguistic / misc ----------------------------------------------
        p.add(nameContains("language", f -> f.nation().language()));
        p.add(nameContains("hash", f -> f.hashing().sha256()));
        p.add(nameContains("uuid", f -> UUID.randomUUID().toString()));
        p.add(nameContains("guid", f -> UUID.randomUUID().toString()));
        p.add(nameContains("token", f -> f.internet().uuid()));
        p.add(nameContains("session_id", f -> UUID.randomUUID().toString()));

        // --- Generic name (fallback that the previous mapping had) -----------
        p.add(name(
            n -> n.equals("name") || n.endsWith("_name") || n.startsWith("name_"),
            f -> f.name().fullName()));

        return List.copyOf(p);
    }

    /* ====================================================================== */
    /* Type fallback patterns                                                 */
    /* ====================================================================== */

    private static List<TypePattern> buildTypePatterns() {
        List<TypePattern> p = new ArrayList<>(20);
        p.add(type(t -> t.contains("uuid"), (f, r) -> UUID.randomUUID().toString()));
        p.add(type(
            t -> t.contains("serial") || t.contains("bigint") || t.contains("smallint")
                || t.contains("int") || t.contains("number") || t.contains("long"),
            (f, r) -> f.number().numberBetween(1L, 1_000_000L)));
        p.add(type(
            t -> t.contains("decimal") || t.contains("numeric") || t.contains("double")
                || t.contains("float") || t.contains("real") || t.contains("money"),
            (f, r) -> f.number().randomDouble(4, 0, 100_000)));
        p.add(type(t -> t.contains("bool"), (f, r) -> f.bool().bool()));
        p.add(type(
            t -> t.contains("timestamp") || t.contains("datetime"),
            (f, r) -> f.date().past(3650, TimeUnit.DAYS).toInstant()));
        p.add(type(
            t -> t.equals("date") || t.startsWith("date "),
            (f, r) -> f.date().past(3650, TimeUnit.DAYS).toInstant()
                .atOffset(ZoneOffset.UTC).toLocalDate()));
        p.add(type(t -> t.contains("date"),
            (f, r) -> f.date().past(3650, TimeUnit.DAYS).toInstant()
                .atOffset(ZoneOffset.UTC).toLocalDate()));
        p.add(type(t -> t.contains("time"),
            (f, r) -> LocalTime.of(r.nextInt(24), r.nextInt(60), r.nextInt(60))));
        p.add(type(t -> t.contains("json") || t.contains("jsonb") || t.contains("object"),
            (f, r) -> Map.of(
                "key", f.lorem().word(),
                "value", f.lorem().word(),
                "n", f.number().numberBetween(1, 100))));
        p.add(type(t -> t.contains("blob") || t.contains("binary") || t.contains("bytea"),
            (f, r) -> f.lorem().characters(16)));
        p.add(type(t -> t.contains("array"),
            (f, r) -> List.of(f.lorem().word(), f.lorem().word(), f.lorem().word())));
        p.add(type(t -> t.contains("vector") || t.contains("embedding"),
            (f, r) -> randomVector(r)));
        p.add(type(t -> t.contains("char") || t.contains("text") || t.contains("string")
                || t.contains("clob") || t.isEmpty(),
            (f, r) -> f.lorem().sentence(6)));
        return List.copyOf(p);
    }

    private static double[] randomVector(Random r) {
        double[] v = new double[8];
        for (int i = 0; i < v.length; i++) v[i] = r.nextDouble();
        return v;
    }

    /* ====================================================================== */
    /* Pattern factory helpers                                                 */
    /* ====================================================================== */

    private static NamePattern name(Predicate<String> matcher, Function<Faker, Object> producer) {
        return new NamePattern(matcher, producer);
    }

    private static NamePattern nameEquals(String exact, Function<Faker, Object> producer) {
        return new NamePattern(n -> n.equals(exact), producer);
    }

    private static NamePattern nameContains(String fragment, Function<Faker, Object> producer) {
        return new NamePattern(n -> n.contains(fragment), producer);
    }

    private static TypePattern type(Predicate<String> matcher, BiFunction<Faker, Random, Object> producer) {
        return new TypePattern(matcher, producer);
    }

    private record NamePattern(Predicate<String> match, Function<Faker, Object> producer) {}

    private record TypePattern(Predicate<String> match, BiFunction<Faker, Random, Object> producer) {}
}
