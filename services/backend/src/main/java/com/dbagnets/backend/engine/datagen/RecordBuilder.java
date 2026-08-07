package com.dbagnets.backend.engine.datagen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.engine.cascade.CascadeEdge;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalSchema;

@Component
public class RecordBuilder {

    private final FakerCatalog fakerCatalog;

    public RecordBuilder() {
        this(new FakerCatalog());
    }

    public RecordBuilder(FakerCatalog fakerCatalog) {
        this.fakerCatalog = fakerCatalog;
    }

    public Map<String, List<GeneratedRow>> generateAll(
            LogicalSchema schema, CascadePlan plan, PrimaryKeyVault vault) {
        Map<String, List<GeneratedRow>> rowsByEntity = new LinkedHashMap<>();
        for (CascadeNode node : plan.nodesInInsertOrder()) {
            LogicalEntity entity = schema.requireEntity(node.entityName());
            List<GeneratedRow> rows = new ArrayList<>((int) node.recordCount());
            Map<String, String> fkColumnToParent = indexFkColumns(node.incomingFromParents());
            for (long i = 0; i < node.recordCount(); i++) {
                rows.add(generateOne(entity, fkColumnToParent, vault));
            }
            rowsByEntity.put(node.entityName(), rows);
        }
        return rowsByEntity;
    }

    public GeneratedRow generateOne(
            LogicalEntity entity, Map<String, String> fkColumnToParent, PrimaryKeyVault vault) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(entity.attributes().size());
        String logicalId = null;
        for (LogicalAttribute attr : entity.attributes()) {
            String colKey = attr.name().toLowerCase(Locale.ROOT);
            if (attr.isPrimaryKey()) {
                String pk = UUID.randomUUID().toString();
                values.put(attr.name(), pk);
                vault.append(entity.name(), pk);
                logicalId = pk;
                continue;
            }
            String parentEntity = fkColumnToParent.get(colKey);
            if (parentEntity != null) {
                values.put(attr.name(), vault.randomPk(parentEntity));
                continue;
            }
            values.put(attr.name(), fakerCatalog.generate(attr));
        }
        if (logicalId == null) {
            logicalId = UUID.randomUUID().toString();
        }
        return new GeneratedRow(entity.name(), logicalId, values);
    }

    private static Map<String, String> indexFkColumns(List<CascadeEdge> edges) {
        Map<String, String> map = new HashMap<>();
        for (CascadeEdge edge : edges) {
            map.put(edge.fkColumnInChild().toLowerCase(Locale.ROOT), edge.parentEntity());
        }
        return map;
    }
}
