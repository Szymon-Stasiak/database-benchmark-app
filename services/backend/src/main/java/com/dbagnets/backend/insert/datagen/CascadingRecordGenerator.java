package com.dbagnets.backend.insert.datagen;

import com.dbagnets.backend.insert.cascade.CascadePlan;
import com.dbagnets.backend.insert.cascade.EntityNode;
import com.dbagnets.backend.insert.cascade.FkColumnHeuristics;
import com.dbagnets.backend.insert.cascade.PrimaryKeyRegistry;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates records for every entity in a {@link CascadePlan} in topological order
 * (parents first), maintaining FK integrity via {@link PrimaryKeyRegistry}.
 *
 * <p>For each entity:
 * <ol>
 *   <li>For every PK attribute, generate a value via {@link DataFakerService} and immediately
 *       register it in the {@link PrimaryKeyRegistry} so dependent children can reference it.</li>
 *   <li>For every FK attribute (inferred via {@link FkColumnHeuristics} from the entity's parent
 *       list), pull a random already-registered PK from the parent registry and use it instead of
 *       fake data — this is what makes the generated dataset referentially consistent.</li>
 *   <li>All other attributes fall through to the standard {@link DataFakerService} generator.</li>
 * </ol>
 *
 * <p>Returned {@link CascadeData} is immutable and safe to share across the parallel per-DB
 * insert tasks. Generation is single-threaded; this is fine because it runs once per insert run
 * and produces small in-memory data (record count is capped at 100k per entity).
 */
@Component
public class CascadingRecordGenerator {

    private final DataFakerService dataFakerService;

    public CascadingRecordGenerator(DataFakerService dataFakerService) {
        this.dataFakerService = dataFakerService;
    }

    public CascadeData generate(CascadePlan plan, PrimaryKeyRegistry registry) {
        Map<String, List<GeneratedRecord>> recordsByEntity = new LinkedHashMap<>();
        Map<String, String> pkColumnByEntity = new LinkedHashMap<>();

        for (EntityNode node : plan.orderedEntities()) {
            LogicalEntity entity = node.entity();
            List<LogicalAttribute> attributes = entity.attributesOrEmpty();
            String pkColumn = primaryKeyColumn(attributes);
            if (pkColumn != null) pkColumnByEntity.put(entity.name(), pkColumn);

            Map<String, String> fkColumns = FkColumnHeuristics.mapForChild(entity, node.parents());

            List<GeneratedRecord> records = new ArrayList<>(node.recordCount());
            List<Object> generatedPks = new ArrayList<>(node.recordCount());

            for (int i = 0; i < node.recordCount(); i++) {
                Map<String, Object> values = new LinkedHashMap<>(attributes.size());
                for (LogicalAttribute attr : attributes) {
                    Object value;
                    String parentForFk = parentForColumn(fkColumns, attr.name());
                    if (parentForFk != null) {
                        Object parentPk = registry.randomFk(parentForFk);
                        value = parentPk != null ? parentPk : dataFakerService.generate(attr);
                    } else {
                        value = dataFakerService.generate(attr);
                    }
                    values.put(attr.name(), value);
                }
                GeneratedRecord rec = new GeneratedRecord(values);
                records.add(rec);
                if (pkColumn != null) {
                    Object pk = rec.get(pkColumn);
                    if (pk != null) generatedPks.add(pk);
                }
            }
            if (!generatedPks.isEmpty()) {
                registry.record(entity.name(), generatedPks);
            }
            recordsByEntity.put(entity.name(), List.copyOf(records));
        }
        return new CascadeData(Map.copyOf(recordsByEntity), Map.copyOf(pkColumnByEntity));
    }

    private static String primaryKeyColumn(List<LogicalAttribute> attributes) {
        for (LogicalAttribute a : attributes) {
            if (a.constraintsOrDefault().isPrimaryKey()) return a.name();
        }
        return null;
    }

    private static String parentForColumn(Map<String, String> fkColumns, String columnName) {
        for (var e : fkColumns.entrySet()) {
            if (e.getValue().equalsIgnoreCase(columnName)) return e.getKey();
        }
        return null;
    }

    /**
     * Immutable bundle of generated cascade data.
     *
     * @param recordsByEntity ordered map: entity name → records (in cascade order).
     * @param pkColumnByEntity entity name → its PK attribute name (when one exists).
     */
    public record CascadeData(
        Map<String, List<GeneratedRecord>> recordsByEntity,
        Map<String, String> pkColumnByEntity
    ) {
        public List<GeneratedRecord> recordsFor(String entityName) {
            return recordsByEntity.getOrDefault(entityName, List.of());
        }
    }
}
