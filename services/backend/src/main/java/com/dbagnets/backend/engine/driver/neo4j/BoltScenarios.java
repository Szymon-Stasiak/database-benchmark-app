package com.dbagnets.backend.engine.driver.neo4j;

import com.dbagnets.backend.engine.driver.pg.PgScenarios;
import com.dbagnets.backend.engine.scenario.ScenarioSupport;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class BoltScenarios {

    private BoltScenarios() {
    }

    public static Map<String, Long> executeAggregate(Session session, LogicalSchema schema, String parentEntity, String childEntity) {
        LogicalRelationship rel = ScenarioSupport.findRelationship(schema, parentEntity, childEntity);
        String relType = (rel.parentEntity() + "_" + rel.childEntity()).toUpperCase();
        LogicalAttribute parentPk = schema.requireEntity(parentEntity).primaryKey().orElseThrow(() -> new IllegalStateException(parentEntity + " has no PK"));
        String cypher = "MATCH (p:`" + parentEntity + "`)-[:`" + relType + "`]->(c:`" + childEntity + "`)" + " RETURN p." + parentPk.name() + " AS key, count(c) AS cnt ORDER BY key";
        Result result = session.run(cypher);
        Map<String, Long> grouped = new TreeMap<>();
        while (result.hasNext()) {
            var record = result.next();
            Object key = record.get("key").asObject();
            long cnt = record.get("cnt").asLong();
            if (key != null) grouped.put(String.valueOf(key), cnt);
        }
        result.consume();
        return grouped;
    }

    public static long executeRangeCount(Session session, LogicalSchema schema, String entityName, String attribute, double min, double max) {
        LogicalEntity entity = schema.requireEntity(entityName);
        LogicalAttribute attr = entity.findAttribute(attribute).orElseThrow(() -> new IllegalArgumentException("Attribute " + attribute + " not found"));
        if (!ScenarioSupport.isNumericLike(attr)) {
            throw new IllegalArgumentException("Attribute " + attribute + " is not numeric — type " + attr.dataType());
        }
        String cypher = "MATCH (n:`" + entity.name() + "`) WHERE n." + attr.name() + " >= $min AND n." + attr.name() + " <= $max RETURN count(n) AS cnt";
        Result result = session.run(cypher, Map.of("min", min, "max", max));
        long count = 0L;
        if (result.hasNext()) count = result.next().get("cnt").asLong();
        result.consume();
        return count;
    }

    public static List<String> executeTraversal(Session session, LogicalSchema schema, String startEntity, String startLogicalId, int depth) {
        LogicalAttribute startPk = schema.requireEntity(startEntity).primaryKey().orElseThrow(() -> new IllegalStateException(startEntity + " has no PK"));
        List<PgScenarios.TraversalLevel> chain = PgScenarios.resolveChain(schema, startEntity, depth);
        if (chain.isEmpty()) return List.of();

        Set<String> relTypes = new LinkedHashSet<>();
        Set<String> childLabels = new LinkedHashSet<>();
        for (PgScenarios.TraversalLevel level : chain) {
            relTypes.add((level.parentEntity() + "_" + level.childEntity()).toUpperCase());
            childLabels.add(level.childEntity());
        }
        String relPattern = relTypes.stream().map(t -> "`" + t + "`").collect(Collectors.joining("|"));
        String labelFilter = childLabels.stream().map(l -> "'" + l + "'").collect(Collectors.joining(", "));

        String cypher = "MATCH (start:`" + startEntity + "` {" + startPk.name() + ": $id})" + "-[:" + relPattern + "*1.." + chain.size() + "]->(d)" + " WHERE any(lbl IN labels(d) WHERE lbl IN [" + labelFilter + "])" + " RETURN DISTINCT properties(d) AS props";
        Result result = session.run(cypher, Map.of("id", startLogicalId));
        List<String> ids = new ArrayList<>();
        while (result.hasNext()) {
            var record = result.next();
            Object props = record.get("props").asObject();
            if (props instanceof Map<?, ?> map) {
                Object pk = extractPk(map);
                if (pk != null) ids.add(String.valueOf(pk));
            }
        }
        result.consume();
        Collections.sort(ids);
        return ids;
    }

    private static Object extractPk(Map<?, ?> props) {
        for (Map.Entry<?, ?> e : props.entrySet()) {
            String key = String.valueOf(e.getKey()).toLowerCase();
            if (key.endsWith("_id") || key.equals("id")) {
                return e.getValue();
            }
        }
        return null;
    }

}