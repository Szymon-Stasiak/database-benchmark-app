package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class ElasticsearchInsertStrategy extends HttpApiInsertStrategy {

    public ElasticsearchInsertStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    protected String singleEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/" + index(ctx) + "/_doc";
    }

    @Override
    protected String bulkEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/" + index(ctx) + "/_bulk";
    }

    @Override
    protected Map<String, String> headers(InsertContext ctx) {
        // _bulk requires application/x-ndjson; _doc accepts application/json.
        if (ctx.mode() == com.dbagnets.backend.insert.entity.InsertMode.SINGLE) {
            return Map.of("Content-Type", "application/json");
        }
        return Map.of("Content-Type", "application/x-ndjson");
    }

    @Override
    protected String singleBody(InsertContext ctx, GeneratedRecord record) {
        return ValueFormatter.jsonLiteral(recordAsJson(record), mapper);
    }

    @Override
    protected String bulkBody(InsertContext ctx, List<GeneratedRecord> records) {
        StringBuilder sb = new StringBuilder(records.size() * 96);
        for (GeneratedRecord r : records) {
            sb.append("{\"index\":{}}\n");
            sb.append(ValueFormatter.jsonLiteral(recordAsJson(r), mapper)).append("\n");
        }
        return sb.toString();
    }

    private String index(InsertContext ctx) {
        // Elasticsearch requires lowercase index names.
        return ctx.entityName().toLowerCase();
    }
}
