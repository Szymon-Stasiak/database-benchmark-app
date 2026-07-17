package com.dbagnets.backend.benchmark.setup.internal;

import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class BenchmarkBundleService {

    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String LOGICAL_SCHEMA_ENTRY = "logical_schema.json";
    private static final String SCRIPTS_DIR = "scripts/";
    private static final String EMBEDDING_MAPPINGS_DIR = "embedding_mappings/";

    private static final Map<String, String> SCRIPT_EXTENSIONS = Map.ofEntries(
            Map.entry("postgresql", "sql"),
            Map.entry("mysql", "sql"),
            Map.entry("sqlite", "sql"),
            Map.entry("timescaledb", "sql"),
            Map.entry("neo4j", "cypher"),
            Map.entry("memgraph", "cypher"),
            Map.entry("mongodb", "js"),
            Map.entry("couchdb", "js"),
            Map.entry("elasticsearch", "json"),
            Map.entry("milvus", "py"),
            Map.entry("qdrant", "py"),
            Map.entry("weaviate", "py")
    );

    private final ObjectMapper objectMapper;

    public byte[] pack(Benchmark benchmark) {
        List<BenchmarkDatabase> withScripts = benchmark.getDatabases().stream()
                .filter(db -> db.getScript() != null && !db.getScript().isBlank())
                .toList();
        if (withScripts.isEmpty()) {
            throw new IllegalStateException("Benchmark has no databases with generated scripts to export");
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(baos)) {

            List<BundleManifest.DatabaseEntry> entries = new java.util.ArrayList<>(withScripts.size());

            for (BenchmarkDatabase db : withScripts) {
                String scriptPath = SCRIPTS_DIR + bundleFileName(db, scriptExtension(db.getDbName()));
                writeEntry(zip, scriptPath, db.getScript().getBytes(StandardCharsets.UTF_8));

                String embeddingPath = null;
                if (db.getEmbeddingMappings() != null && !db.getEmbeddingMappings().isBlank()) {
                    embeddingPath = EMBEDDING_MAPPINGS_DIR + bundleFileName(db, "json");
                    writeEntry(zip, embeddingPath, db.getEmbeddingMappings().getBytes(StandardCharsets.UTF_8));
                }

                entries.add(new BundleManifest.DatabaseEntry(
                        db.getDbType().name(),
                        db.getDbName(),
                        db.getDbVersion(),
                        db.getDockerImage(),
                        scriptPath,
                        embeddingPath
                ));
            }

            if (benchmark.getLogicalSchema() != null && !benchmark.getLogicalSchema().isBlank()) {
                writeEntry(zip, LOGICAL_SCHEMA_ENTRY, benchmark.getLogicalSchema().getBytes(StandardCharsets.UTF_8));
            }

            BundleManifest manifest = new BundleManifest(
                    BundleManifest.CURRENT_VERSION,
                    benchmark.getTopic(),
                    benchmark.getDepth(),
                    benchmark.getCreatedAt(),
                    entries
            );
            writeEntry(zip, MANIFEST_ENTRY, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

            zip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build benchmark bundle: " + e.getMessage(), e);
        }
    }

    public ParsedBundle parse(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded bundle is empty");
        }

        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                files.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Uploaded file is not a valid ZIP: " + e.getMessage(), e);
        }

        byte[] manifestBytes = files.get(MANIFEST_ENTRY);
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Bundle is missing manifest.json");
        }
        BundleManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestBytes, BundleManifest.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("manifest.json is malformed: " + e.getMessage(), e);
        }
        if (manifest.databases() == null || manifest.databases().isEmpty()) {
            throw new IllegalArgumentException("Bundle manifest contains no databases");
        }

        Map<String, String> scripts = new HashMap<>();
        Map<String, String> embeddingMappings = new HashMap<>();
        for (BundleManifest.DatabaseEntry entry : manifest.databases()) {
            byte[] scriptData = files.get(entry.scriptFile());
            if (scriptData == null) {
                throw new IllegalArgumentException("Bundle is missing script file: " + entry.scriptFile());
            }
            String key = dbKey(entry.dbName(), entry.dbVersion());
            scripts.put(key, new String(scriptData, StandardCharsets.UTF_8));
            if (entry.embeddingMappingsFile() != null) {
                byte[] embeddingData = files.get(entry.embeddingMappingsFile());
                if (embeddingData == null) {
                    throw new IllegalArgumentException("Bundle is missing embedding mappings file: " + entry.embeddingMappingsFile());
                }
                embeddingMappings.put(key, new String(embeddingData, StandardCharsets.UTF_8));
            }
        }

        byte[] logicalSchemaBytes = files.get(LOGICAL_SCHEMA_ENTRY);
        String logicalSchemaJson = logicalSchemaBytes != null
                ? new String(logicalSchemaBytes, StandardCharsets.UTF_8)
                : null;

        return new ParsedBundle(manifest, scripts, embeddingMappings, logicalSchemaJson);
    }

    public static String dbKey(String dbName, String dbVersion) {
        return dbName.toLowerCase(Locale.ROOT) + "|" + dbVersion;
    }

    private static String bundleFileName(BenchmarkDatabase db, String extension) {
        return slug(db.getDbName()) + "_" + slug(db.getDbVersion()) + "." + extension;
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static String scriptExtension(String dbName) {
        return SCRIPT_EXTENSIONS.getOrDefault(dbName.toLowerCase(Locale.ROOT), "txt");
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    public record ParsedBundle(
            BundleManifest manifest,
            Map<String, String> scripts,
            Map<String, String> embeddingMappings,
            String logicalSchemaJson
    ) {}
}
