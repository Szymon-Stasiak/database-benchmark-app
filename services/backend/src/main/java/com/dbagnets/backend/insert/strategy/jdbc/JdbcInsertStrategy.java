package com.dbagnets.backend.insert.strategy.jdbc;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.strategy.Batch;
import com.dbagnets.backend.insert.strategy.BatchProgressCallback;
import com.dbagnets.backend.insert.strategy.DatabaseInsertStrategy;
import com.dbagnets.backend.insert.strategy.InsertContext;
import com.dbagnets.backend.insert.strategy.InsertOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract JDBC insert strategy: spins up a Hikari pool (size = {@code workerCount}), partitions
 * the records into {@link Batch}es according to the {@link InsertMode}, and dispatches them to
 * {@code workerCount} Java virtual threads.
 *
 * <p>Each virtual thread holds one connection for the duration of the phase and pulls batches off
 * a shared {@link BlockingQueue}. The strategy times only the in-driver
 * {@link PreparedStatement#executeBatch()} calls and reports the wall-clock window between the
 * first batch start and the last batch end — that is the "honest" benchmark figure the user
 * asked for, free of process spawn or stdout overhead.
 *
 * <p>Subclasses only provide the JDBC URL builder; the rest of the path is shared.
 */
public abstract class JdbcInsertStrategy implements DatabaseInsertStrategy {

    private static final Logger log = LoggerFactory.getLogger(JdbcInsertStrategy.class);

    /** Sentinel batch that signals workers to stop draining the queue. */
    private static final Batch POISON = new Batch(-1, -1, List.of());

    /** Shared ObjectMapper for serialising Map/List → proper JSON when binding to JSON / JSONB
     *  columns. Java's {@code Map.toString()} produces {@code {a=b}} which the server rejects. */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext ctx, BatchProgressCallback progress) {
        if (ctx.hostPort() == null) {
            return InsertOutcome.failure("Database has no host port mapping yet", 0);
        }
        int workers = Math.max(1, ctx.effectiveWorkerCount());
        List<Batch> batches = partition(ctx);
        if (batches.isEmpty()) return InsertOutcome.success(0, 0);

        try (HikariDataSource pool = buildPool(ctx, workers)) {
            ResolvedSchema resolved = resolveSchema(pool, ctx);
            return runBatches(ctx, batches, workers, pool, resolved, progress);
        } catch (Exception e) {
            log.warn("JDBC insert failed for {}", ctx.dbName(), e);
            return InsertOutcome.failure("JDBC insert failed: " + e.getMessage(), 0);
        }
    }

    /**
     * Discover the actual table identifier and per-column casing via JDBC metadata.
     *
     * <p>The script-creator's CREATE TABLE statements often use double-quoted identifiers (e.g.
     * {@code CREATE TABLE "Cinema"}), which Postgres stores case-preserved. A naive
     * {@code INSERT INTO Cinema} would fold to {@code cinema} and miss the table. By asking
     * {@link DatabaseMetaData} once per phase we side-step every quoting/folding heuristic and
     * use whatever the engine actually has. Failure is non-fatal — we fall back to the raw
     * entity name so engines without a populated metadata catalog still get a chance.
     */
    private ResolvedSchema resolveSchema(HikariDataSource pool, InsertContext ctx) {
        try (Connection conn = pool.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String actualTable = lookupTable(meta, ctx.entityName());
            if (actualTable == null) actualTable = ctx.entityName();
            Map<String, ColumnMeta> actualColumns = lookupColumns(meta, actualTable);
            try {
                actualColumns = enrichColumnsWithEnums(conn, actualColumns);
            } catch (Exception e) {
                log.debug("Enum enrichment failed for {}; falling back to plain bindings", actualTable, e);
            }
            return new ResolvedSchema(actualTable, actualColumns);
        } catch (Exception e) {
            log.debug("Schema metadata lookup failed for {}; using raw entity name", ctx.entityName(), e);
            return new ResolvedSchema(ctx.entityName(), Map.of());
        }
    }

    private static String lookupTable(DatabaseMetaData meta, String entityName) throws Exception {
        String wanted = entityName.toLowerCase(Locale.ROOT);
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && name.toLowerCase(Locale.ROOT).equals(wanted)) return name;
            }
        }
        return null;
    }

    private static Map<String, ColumnMeta> lookupColumns(DatabaseMetaData meta, String actualTable) throws Exception {
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(null, null, actualTable, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name == null) continue;
                int sqlType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                out.put(name.toLowerCase(Locale.ROOT),
                    new ColumnMeta(name, sqlType, typeName, nullable, null, null));
            }
        }
        return out;
    }

    /**
     * Captured per-column metadata used to coerce a generated value to the right driver binding.
     *
     * <ul>
     *   <li>{@code nullable} — drives the "give up and bind NULL" fallback when we can't satisfy
     *       a constraint we don't fully understand.</li>
     *   <li>{@code enumValues} — populated by {@link #enrichColumnsWithEnums} for PG ENUM types,
     *       so the binder picks a real label instead of a faker lorem word.</li>
     *   <li>{@code constraintHint} — populated by {@link #enrichColumnsWithConstraints} for
     *       DOMAIN columns that have a CHECK clause. See {@link ConstraintHint} for the shapes.</li>
     * </ul>
     */
    record ColumnMeta(
        String name, int sqlType, String typeName, boolean nullable,
        List<String> enumValues, ConstraintHint constraintHint
    ) {
        ColumnMeta {
            enumValues = enumValues == null ? null : List.copyOf(enumValues);
        }
        ColumnMeta withEnumValues(List<String> values) {
            return new ColumnMeta(name, sqlType, typeName, nullable, values, constraintHint);
        }
        ColumnMeta withConstraintHint(ConstraintHint hint) {
            return new ColumnMeta(name, sqlType, typeName, nullable, enumValues, hint);
        }
    }

    /**
     * Engine-specific hook: enrich the column metadata with extra information that requires a live
     * connection (e.g. for Postgres, the list of valid labels for each ENUM type and CHECK clauses
     * for each DOMAIN). Default implementation is a no-op; {@link PostgresJdbcStrategy} overrides it.
     */
    protected Map<String, ColumnMeta> enrichColumnsWithEnums(
        Connection conn, Map<String, ColumnMeta> columns
    ) throws Exception {
        return columns;
    }

    /* ====================================================================== */
    /* Worker pool execution                                                   */
    /* ====================================================================== */

    private InsertOutcome runBatches(
        InsertContext ctx,
        List<Batch> batches,
        int workers,
        HikariDataSource pool,
        ResolvedSchema resolved,
        BatchProgressCallback progress
    ) {
        // Drop attributes the actual table doesn't have (script-creator may include attributes
        // in the logical schema that are computed columns / triggers and not in the real DDL).
        List<LogicalAttribute> usableAttrs = filterToActualColumns(ctx.attributes(), resolved.columns());
        if (usableAttrs.isEmpty()) {
            return InsertOutcome.failure(
                "None of the logical attributes match any column in table " + resolved.table(), 0);
        }
        String sql = insertSql(resolved, usableAttrs);
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(batches.size() + workers);
        for (Batch b : batches) queue.add(b);
        for (int i = 0; i < workers; i++) queue.add(POISON);

        AtomicLong firstStartNs = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastEndNs = new AtomicLong(0);
        AtomicInteger doneRecords = new AtomicInteger();
        AtomicInteger doneBatches = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();

        try (ExecutorService exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(exec.submit(() -> workerLoop(
                    ctx, sql, usableAttrs, resolved, pool, queue, firstStartNs, lastEndNs,
                    doneRecords, doneBatches, batches.size(), failure, progress)));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return InsertOutcome.failure("Interrupted: " + ie.getMessage(), 0);
                } catch (ExecutionException ee) {
                    log.warn("JDBC worker raised", ee.getCause());
                    failure.compareAndSet(null, "Worker failed: " + ee.getCause().getMessage());
                }
            }
        }

        if (failure.get() != null) {
            return InsertOutcome.failure(failure.get(), durationMs(firstStartNs, lastEndNs));
        }
        return InsertOutcome.success(doneRecords.get(), durationMs(firstStartNs, lastEndNs));
    }

    private void workerLoop(
        InsertContext ctx,
        String sql,
        List<LogicalAttribute> usableAttrs,
        ResolvedSchema resolved,
        HikariDataSource pool,
        BlockingQueue<Batch> queue,
        AtomicLong firstStartNs,
        AtomicLong lastEndNs,
        AtomicInteger doneRecords,
        AtomicInteger doneBatches,
        int batchCount,
        AtomicReference<String> failure,
        BatchProgressCallback progress
    ) {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(ctx.mode() == InsertMode.SINGLE);
            while (true) {
                Batch batch = queue.poll();
                if (batch == null || batch == POISON) return;
                long t0 = System.nanoTime();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (GeneratedRecord rec : batch.records()) {
                        bindRow(ps, usableAttrs, resolved, rec);
                        if (ctx.mode() == InsertMode.SINGLE) {
                            ps.executeUpdate();
                        } else {
                            ps.addBatch();
                        }
                    }
                    if (ctx.mode() != InsertMode.SINGLE) {
                        ps.executeBatch();
                        conn.commit();
                    }
                }
                long t1 = System.nanoTime();
                firstStartNs.updateAndGet(prev -> Math.min(prev, t0));
                lastEndNs.updateAndGet(prev -> Math.max(prev, t1));
                int done = doneRecords.addAndGet(batch.size());
                int idx = doneBatches.getAndIncrement();
                progress.onBatch(idx, batchCount, done);
            }
        } catch (Exception e) {
            log.warn("JDBC worker failed on {} mode {}", ctx.dbName(), ctx.mode(), e);
            failure.compareAndSet(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long durationMs(AtomicLong firstStartNs, AtomicLong lastEndNs) {
        long s = firstStartNs.get();
        long e = lastEndNs.get();
        if (s == Long.MAX_VALUE || e <= s) return 0;
        return (e - s) / 1_000_000L;
    }

    /* ====================================================================== */
    /* Batch partitioning + SQL                                                */
    /* ====================================================================== */

    private static List<Batch> partition(InsertContext ctx) {
        List<GeneratedRecord> records = ctx.records();
        if (records.isEmpty()) return List.of();

        int size = switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.effectiveBatchSize());
            case BULK -> records.size();
        };

        List<Batch> out = new ArrayList<>();
        int total = (records.size() + size - 1) / size;
        for (int i = 0, idx = 0; i < records.size(); i += size, idx++) {
            int end = Math.min(i + size, records.size());
            out.add(new Batch(idx, total, records.subList(i, end)));
        }
        return out;
    }

    private String insertSql(ResolvedSchema resolved, List<LogicalAttribute> attrs) {
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        boolean first = true;
        for (LogicalAttribute a : attrs) {
            if (!first) { cols.append(", "); placeholders.append(", "); }
            ColumnMeta col = resolved.columns().get(a.name().toLowerCase(Locale.ROOT));
            String actual = col != null ? col.name() : a.name();
            cols.append(needsQuoting(actual) ? quoteIdent(actual) : actual);
            placeholders.append('?');
            first = false;
        }
        String table = needsQuoting(resolved.table()) ? quoteIdent(resolved.table()) : resolved.table();
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ")";
    }

    /** Identifiers that contain anything other than ASCII lower-case + digits + underscore round-trip
     *  safely only when quoted. Lower-case-only identifiers can stay unquoted, which keeps the SQL
     *  readable in logs. */
    private static boolean needsQuoting(String ident) {
        if (ident == null || ident.isEmpty()) return false;
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return true;
        }
        return false;
    }

    private static List<LogicalAttribute> filterToActualColumns(
        List<LogicalAttribute> attrs, Map<String, ColumnMeta> actualColumns
    ) {
        if (actualColumns.isEmpty()) return attrs; // metadata lookup failed — keep best-effort path
        Set<String> have = new LinkedHashSet<>(actualColumns.keySet());
        List<LogicalAttribute> out = new ArrayList<>(attrs.size());
        for (LogicalAttribute a : attrs) {
            if (have.contains(a.name().toLowerCase(Locale.ROOT))) out.add(a);
        }
        return out;
    }

    /** Table + per-column metadata captured once at the start of the phase via JDBC introspection. */
    protected record ResolvedSchema(String table, Map<String, ColumnMeta> columns) {}

    private void bindRow(
        PreparedStatement ps,
        List<LogicalAttribute> attrs,
        ResolvedSchema resolved,
        GeneratedRecord rec
    ) throws Exception {
        for (int i = 0; i < attrs.size(); i++) {
            LogicalAttribute attr = attrs.get(i);
            ColumnMeta col = resolved.columns().get(attr.name().toLowerCase(Locale.ROOT));
            Object value = rec.get(attr.name());
            bindValue(ps, i + 1, col, value);
        }
    }

    /**
     * Translate a generated value into the right JDBC binding for the target column.
     *
     * <p>Two surprises this method papers over:
     * <ul>
     *   <li>{@code DataFakerService} returns {@code String} for UUID-ish attributes (cheap
     *       portability), but Postgres' UUID column rejects VARCHAR bindings. Convert the string
     *       to {@link java.util.UUID} so the driver picks the correct OID.</li>
     *   <li>For JSON/JSONB columns, send the value as {@code Types.OTHER} so the server runs the
     *       implicit text → jsonb cast. Without the type hint the driver picks VARCHAR and the
     *       column rejects it.</li>
     * </ul>
     */
    protected void bindValue(PreparedStatement ps, int idx, ColumnMeta col, Object value) throws Exception {
        // ENUM substitution: random pick from the type's labels.
        if (col != null && col.enumValues() != null && !col.enumValues().isEmpty()) {
            List<String> values = col.enumValues();
            String pick = values.get(ThreadLocalRandom.current().nextInt(values.size()));
            ps.setObject(idx, pick, Types.OTHER);
            return;
        }

        // DOMAIN-with-CHECK substitution: numeric range / allowed string list / NULL fallback.
        if (col != null && col.constraintHint() != null) {
            switch (col.constraintHint()) {
                case ConstraintHint.NumericRange r -> {
                    double v = r.min() + ThreadLocalRandom.current().nextDouble() * (r.max() - r.min());
                    // Bind through the type-specific JDBC setter, NOT via Types.OTHER text round-trip
                    // (which serializes Long as "251.0" and trips integer columns). Pick the
                    // narrowest setter that matches the column's declared SQL type.
                    switch (col.sqlType()) {
                        case Types.SMALLINT, Types.TINYINT, Types.INTEGER -> {
                            long n = Math.max((long) Math.floor(r.min()), Math.round(v));
                            ps.setInt(idx, (int) n);
                        }
                        case Types.BIGINT -> {
                            long n = Math.max((long) Math.floor(r.min()), Math.round(v));
                            ps.setLong(idx, n);
                        }
                        case Types.REAL, Types.FLOAT -> ps.setFloat(idx, (float) v);
                        case Types.DOUBLE -> ps.setDouble(idx, v);
                        default -> {
                            // NUMERIC/DECIMAL columns, or non-standard wrappers like DOMAINs over
                            // numeric. Send as BigDecimal so the value carries no trailing ".0".
                            double rounded = Math.round(v * 10_000d) / 10_000d;
                            ps.setBigDecimal(idx, java.math.BigDecimal.valueOf(rounded));
                        }
                    }
                    return;
                }
                case ConstraintHint.AllowedValues a -> {
                    List<String> vs = a.values();
                    String pick = vs.get(ThreadLocalRandom.current().nextInt(vs.size()));
                    ps.setObject(idx, pick, Types.OTHER);
                    return;
                }
                case ConstraintHint.RegexHint rh -> {
                    // For known regex kinds the faker-generated value (if produced via name-based
                    // heuristics) is usually already valid. Only fall through to NULL when nullable
                    // and the value clearly isn't going to match (no @ for EMAIL, no http for URL).
                    boolean clearlyWrong = switch (rh.kind()) {
                        case EMAIL -> !(value instanceof String s) || !s.contains("@");
                        case URL -> !(value instanceof String s) || !s.toLowerCase(Locale.ROOT).contains("http");
                        case OTHER -> true;
                    };
                    if (clearlyWrong && col.nullable()) {
                        ps.setNull(idx, col.sqlType());
                        return;
                    }
                    // fall through to default binding
                }
            }
        }

        if (value == null) {
            if (col == null) { ps.setObject(idx, null); return; }
            if (col.nullable()) { ps.setNull(idx, col.sqlType()); return; }
            // NOT NULL column but the faker rolled a null (the LogicalSchema's nullable hint can
            // disagree with the actual DB). Substitute a type-appropriate non-null placeholder so
            // the row still inserts and the benchmark keeps going.
            value = notNullFallback(col);
        }
        Object normalized = normalize(value);

        if (col != null) {
            String typeName = col.typeName().toLowerCase(Locale.ROOT);
            // UUID needs a real java.util.UUID — the driver picks the right OID from there.
            if (typeName.equals("uuid") && normalized instanceof String s) {
                try {
                    ps.setObject(idx, UUID.fromString(s));
                    return;
                } catch (IllegalArgumentException ignored) { /* fall through */ }
            }
            // JSON / JSONB columns require valid JSON text. The faker only knows column NAMES, so
            // a column called {@code genres} might receive a bare string like {@code "Horror"}
            // which the JSON parser then rejects ("Token Horror is invalid"). Always coerce to
            // valid JSON: Maps/Lists are already JSON-serialised by `normalize`; bare strings get
            // wrapped as a single-element array; anything else becomes {"value": "<toString>"}.
            if (typeName.contains("json")) {
                String json = coerceToJson(normalized);
                ps.setObject(idx, json, Types.OTHER);
                return;
            }
            // Custom/non-standard SQL types: send with Types.OTHER so the engine performs the
            // implicit text → enum / text → domain / text → custom-domain cast.
            if (!isStandardScalar(col.sqlType(), typeName)) {
                ps.setObject(idx, normalized.toString(), Types.OTHER);
                return;
            }
        }
        ps.setObject(idx, normalized);
    }

    /** Make absolutely sure the value going into a JSON column is parseable JSON. */
    private static String coerceToJson(Object normalized) {
        if (normalized == null) return "null";
        String s = normalized.toString().trim();
        if (s.isEmpty()) return "{}";
        // Already-shaped JSON (Map → "{...}", List → "[...]", numeric/boolean literals): use as-is.
        char first = s.charAt(0);
        if (first == '{' || first == '[' || first == '"'
            || (first >= '0' && first <= '9') || first == '-'
            || s.equals("true") || s.equals("false") || s.equals("null")) {
            return s;
        }
        // Bare string like "Horror" → wrap as a JSON array so columns with GIN indexes (which
        // typically target jsonb arrays) still work.
        try {
            return JSON_MAPPER.writeValueAsString(List.of(s));
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Type-appropriate placeholder used when a NOT NULL column receives a null from the faker
     *  (which trusts the LogicalSchema's possibly-wrong {@code nullable} hint over the real DB). */
    private static Object notNullFallback(ColumnMeta col) {
        String tn = col.typeName().toLowerCase(Locale.ROOT);
        if (tn.equals("uuid")) return UUID.randomUUID();
        // Default to 1, not 0 — most CHECK constraints insist on "> 0" / "IS POSITIVE", which
        // a literal 0 would violate. 1 is a safer non-null placeholder.
        if (tn.contains("int") || tn.contains("serial") || tn.contains("number")) return 1L;
        if (tn.contains("numeric") || tn.contains("decimal") || tn.contains("real")
            || tn.contains("float") || tn.contains("double") || tn.contains("money")) return 1.0;
        if (tn.contains("bool")) return false;
        if (tn.contains("date")) return java.sql.Date.valueOf(java.time.LocalDate.now());
        if (tn.contains("timestamp") || tn.contains("datetime"))
            return java.sql.Timestamp.from(java.time.Instant.now());
        if (tn.contains("time")) return java.sql.Time.valueOf(java.time.LocalTime.now());
        if (tn.contains("json")) return "{}";
        // Everything else (varchar, text, char, custom DOMAIN over text, ENUM-as-varchar):
        // a short non-empty string that satisfies most CHECK lengths.
        return "n/a";
    }

    /** Standard SQL types that all JDBC drivers natively understand from a plain Java value — for
     *  everything else (Postgres ENUMs reported as VARCHAR, DOMAINs reported as DISTINCT, JSONB,
     *  custom user-defined types) we fall back to {@link Types#OTHER}. */
    private static boolean isStandardScalar(int sqlType, String typeName) {
        switch (sqlType) {
            case Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
                 Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR,
                 Types.LONGNVARCHAR, Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
                 Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB,
                 Types.BOOLEAN, Types.CLOB, Types.NCLOB:
                // Drill deeper — Postgres reports ENUMs as VARCHAR even though they are not.
                // Heuristic: a custom Postgres ENUM/DOMAIN type-name typically contains an
                // underscore and is not one of the well-known builtins.
                return isBuiltinTypeName(typeName);
            default:
                return false;
        }
    }

    private static final Set<String> KNOWN_BUILTIN_TYPE_NAMES = Set.of(
        "varchar", "character varying", "char", "character", "bpchar", "text", "citext",
        "int", "int2", "int4", "int8", "smallint", "integer", "bigint",
        "serial", "bigserial", "smallserial",
        "numeric", "decimal", "real", "float4", "float8", "double precision", "money",
        "bool", "boolean", "bit", "varbit",
        "date", "time", "timetz", "timestamp", "timestamptz",
        "bytea", "blob",
        "tinytext", "mediumtext", "longtext", "tinyblob", "mediumblob", "longblob"
    );

    private static boolean isBuiltinTypeName(String typeName) {
        if (typeName == null) return true;
        return KNOWN_BUILTIN_TYPE_NAMES.contains(typeName);
    }

    private static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.LocalDate d) return java.sql.Date.valueOf(d);
        if (value instanceof java.time.LocalTime t) return java.sql.Time.valueOf(t);
        if (value instanceof java.time.Instant i) return java.sql.Timestamp.from(i);
        if (value instanceof double[] arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int j = 0; j < arr.length; j++) {
                if (j > 0) sb.append(',');
                sb.append(arr[j]);
            }
            return sb.append(']').toString();
        }
        // Maps and nested collections — serialise as proper JSON so JSON/JSONB columns accept
        // them and TEXT columns still get a sensible string. Default Java toString() produces
        // {a=b} which is NOT valid JSON.
        if (value instanceof java.util.Map<?, ?> || value instanceof Iterable<?>) {
            try {
                return JSON_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value;
    }

    /* ====================================================================== */
    /* Per-engine extension points                                             */
    /* ====================================================================== */

    private HikariDataSource buildPool(InsertContext ctx, int workers) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl(ctx));
        cfg.setUsername(username(ctx));
        cfg.setPassword(password(ctx));
        cfg.setMaximumPoolSize(workers);
        cfg.setMinimumIdle(workers);
        cfg.setAutoCommit(false);
        cfg.setPoolName("insert-" + ctx.dbName() + "-" + ctx.entityName());
        cfg.setConnectionTimeout(15_000);
        cfg.setInitializationFailTimeout(5_000);
        configurePool(cfg, ctx);
        return new HikariDataSource(cfg);
    }

    /** Per-engine hook for the JDBC URL — e.g. {@code jdbc:postgresql://host:port/benchmark}. */
    protected abstract String jdbcUrl(InsertContext ctx);

    /** Per-engine default username. Override if the container ships a different one. */
    protected abstract String username(InsertContext ctx);

    /** Per-engine default password. Override if the container ships a different one. */
    protected abstract String password(InsertContext ctx);

    /** Hook for engines that need extra Hikari properties (statement cache, ssl, etc.). */
    protected void configurePool(HikariConfig cfg, InsertContext ctx) {}

    /** Engine-specific identifier quoting. Postgres uses {@code "Foo"}, MySQL {@code `Foo`}.
     *  Overridden by {@link MysqlJdbcStrategy} to switch to backticks. */
    protected String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
