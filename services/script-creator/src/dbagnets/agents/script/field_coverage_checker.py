from __future__ import annotations

import re

from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import (
    DatabaseType,
    RelationshipCardinality,
    ValidationStatus,
)
from dbagnets.models.schema import (
    Attribute,
    DocumentEmbeddingMapping,
    Entity,
    LogicalSchema,
)
from dbagnets.models.validation import ValidationResult


class FieldCoverageChecker:

    @property
    def name(self) -> str:
        return "FieldCoverageChecker"

    def validate(
        self,
        target: TargetConfig,
        schema: LogicalSchema,
        script: str,
        embedding_mappings: list[DocumentEmbeddingMapping] | None = None,
    ) -> ValidationResult:
        entity_blocks = self._extract_entity_blocks(target.db_type, script)
        script_lower = script.lower()

        embedded_lookup: dict[str, DocumentEmbeddingMapping] = {}
        if embedding_mappings:
            for m in embedding_mappings:
                if m.is_embedded:
                    embedded_lookup[m.entity_name.lower()] = m

        missing_entities: list[str] = []
        missing_fields: list[str] = []
        skipped_fields: list[str] = []

        for entity in schema.entities:
            entity_key = entity.name.lower()

            graph_fk_attrs: frozenset[str] = frozenset()
            if target.db_type == DatabaseType.GRAPH:
                graph_fk_attrs = self._get_graph_fk_attributes(
                    entity.name, schema,
                )

            if entity_key in embedded_lookup:
                self._check_embedded_entity(
                    entity,
                    embedded_lookup[entity_key],
                    entity_blocks,
                    script_lower,
                    target.db_type,
                    missing_entities,
                    missing_fields,
                    skipped_fields,
                    graph_fk_attrs=graph_fk_attrs,
                )
                continue

            block = self._resolve_entity_block(
                entity.name, entity_key, entity_blocks, target.db_type,
            )
            if block is None:
                if self._word_present(entity_key, script_lower):
                    self._check_entity_fields(
                        entity, script_lower, target.db_type,
                        missing_fields, skipped_fields,
                        context="entity block not parsed, searched full script",
                        graph_fk_attrs=graph_fk_attrs,
                    )
                else:
                    missing_entities.append(
                        f"Entity '{entity.name}' not found in script"
                    )
                continue

            self._check_entity_fields(
                entity, block, target.db_type, missing_fields, skipped_fields,
                graph_fk_attrs=graph_fk_attrs,
            )

        self._check_relationship_attributes(
            schema, script_lower, entity_blocks, target.db_type, missing_fields,
        )

        if not missing_entities and not missing_fields:
            details = ""
            if skipped_fields:
                details = (
                    f"Skipped {len(skipped_fields)} graph properties "
                    f"(unconstrained or FK): {', '.join(skipped_fields)}"
                )
            return ValidationResult(
                agent_name=self.name,
                status=ValidationStatus.PASS,
                feedback="All LogicalSchema fields are present in the database script.",
                details=details,
            )

        todos = missing_entities + missing_fields
        return ValidationResult(
            agent_name=self.name,
            status=ValidationStatus.FAIL,
            feedback=(
                f"{len(missing_entities)} missing entities and "
                f"{len(missing_fields)} missing fields in script."
            ),
            todos=todos,
        )

    # ------------------------------------------------------------------
    # Entity block extraction per database type
    # ------------------------------------------------------------------

    def _extract_entity_blocks(
        self, db_type: DatabaseType, script: str,
    ) -> dict[str, str]:
        extractors = {
            DatabaseType.RELATIONAL: self._extract_sql_blocks,
            DatabaseType.TIME_SERIES: self._extract_sql_blocks,
            DatabaseType.DOCUMENT: self._extract_document_blocks,
            DatabaseType.GRAPH: self._extract_graph_blocks,
            DatabaseType.VECTOR: self._extract_vector_blocks,
            DatabaseType.KEY_VALUE: self._extract_kv_blocks,
        }
        return extractors[db_type](script)

    _TABLE_RE = re.compile(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
        r"(?:\w+\.)?\"?(\w+)\"?\s*\(",
        re.IGNORECASE,
    )
    _ALTER_RE = re.compile(
        r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?"
        r"(?:\w+\.)?\"?(\w+)\"?\s+(.*?);",
        re.IGNORECASE | re.DOTALL,
    )

    def _extract_sql_blocks(self, script: str) -> dict[str, str]:
        result: dict[str, list[str]] = {}

        for match in self._TABLE_RE.finditer(script):
            name = match.group(1).lower()
            block = self._extract_paren_block(script, match.end() - 1)
            if block is not None:
                result.setdefault(name, []).append(block)

        for match in self._ALTER_RE.finditer(script):
            name = match.group(1).lower()
            result.setdefault(name, []).append(match.group(2))

        return {k: "\n".join(v) for k, v in result.items()}

    _COLLECTION_RE = re.compile(
        r"db\.createCollection\(\s*[\"'](\w+)[\"']", re.IGNORECASE,
    )

    def _extract_document_blocks(self, script: str) -> dict[str, str]:
        result: dict[str, str] = {}

        for match in self._COLLECTION_RE.finditer(script):
            name = match.group(1).lower()
            paren_pos = script.find("(", match.start())
            block = self._extract_paren_block(script, paren_pos)
            if block is not None:
                result[name] = block

        return result

    _NODE_LABEL_RE = re.compile(
        r"(?:FOR|ON)\s*\(\s*\w+\s*:\s*(\w+)\s*\)", re.IGNORECASE,
    )

    def _extract_graph_blocks(self, script: str) -> dict[str, str]:
        result: dict[str, list[str]] = {}

        for match in self._NODE_LABEL_RE.finditer(script):
            label = match.group(1).lower()
            stmt_start = max(
                script.rfind("\n", 0, match.start()) + 1,
                script.rfind(";", 0, match.start()) + 1,
            )
            stmt_end = script.find(";", match.end())
            if stmt_end == -1:
                stmt_end = len(script)
            result.setdefault(label, []).append(script[stmt_start : stmt_end + 1])

        return {k: "\n".join(v) for k, v in result.items()}

    def _extract_vector_blocks(self, script: str) -> dict[str, str]:
        blocks = self._extract_sql_blocks(script)
        if blocks:
            return blocks

        result: dict[str, list[str]] = {}

        coll_re = re.compile(r"Collection\(\s*[\"'](\w+)[\"']")
        for match in coll_re.finditer(script):
            name = match.group(1).lower()
            block_start = max(0, script.rfind("\n\n", 0, match.start()))
            block_end = script.find("\n\n", match.end())
            if block_end == -1:
                block_end = len(script)
            result.setdefault(name, []).append(script[block_start:block_end])

        field_re = re.compile(
            r"FieldSchema\(\s*name\s*=\s*[\"'](\w+)[\"']", re.IGNORECASE,
        )
        for match in field_re.finditer(script):
            nearest_coll = None
            nearest_dist = len(script)
            for cname, _ in result.items():
                for cm in coll_re.finditer(script):
                    if cm.group(1).lower() == cname and cm.start() > match.start():
                        dist = cm.start() - match.start()
                        if dist < nearest_dist:
                            nearest_dist = dist
                            nearest_coll = cname
            if nearest_coll:
                result.setdefault(nearest_coll, []).append(match.group(0))

        if result:
            return {k: "\n".join(v) for k, v in result.items()}

        class_re = re.compile(r"[\"']class[\"']\s*:\s*[\"'](\w+)[\"']")
        for match in class_re.finditer(script):
            name = match.group(1).lower()
            brace_pos = script.rfind("{", 0, match.start())
            if brace_pos != -1:
                block = self._extract_brace_block(script, brace_pos)
                if block:
                    result.setdefault(name, []).append(block)

        return {k: "\n".join(v) for k, v in result.items()}

    _FT_CREATE_RE = re.compile(
        r"FT\.CREATE\s+\S+\s+.*?SCHEMA\s+(.*?)(?:;|\n\n|$)",
        re.IGNORECASE | re.DOTALL,
    )
    _FT_PREFIX_RE = re.compile(
        r"PREFIX\s+\d+\s+[\"']?(\w+)", re.IGNORECASE,
    )

    def _extract_kv_blocks(self, script: str) -> dict[str, str]:
        result: dict[str, list[str]] = {}

        for match in self._FT_CREATE_RE.finditer(script):
            full_stmt = match.group(0)
            prefix_match = self._FT_PREFIX_RE.search(full_stmt)
            if prefix_match:
                name = prefix_match.group(1).lower().rstrip(":")
                result.setdefault(name, []).append(full_stmt)

        return {k: "\n".join(v) for k, v in result.items()}

    # ------------------------------------------------------------------
    # Field checking
    # ------------------------------------------------------------------

    def _check_embedded_entity(
        self,
        entity: Entity,
        mapping: DocumentEmbeddingMapping,
        entity_blocks: dict[str, str],
        script_lower: str,
        db_type: DatabaseType,
        missing_entities: list[str],
        missing_fields: list[str],
        skipped_fields: list[str],
        graph_fk_attrs: frozenset[str] = frozenset(),
    ) -> None:
        parent_key = mapping.parent_entity.lower() if mapping.parent_entity else None
        if parent_key and parent_key in entity_blocks:
            self._check_entity_fields(
                entity,
                entity_blocks[parent_key],
                db_type,
                missing_fields,
                skipped_fields,
                context=f"embedded in '{mapping.parent_entity}'",
                graph_fk_attrs=graph_fk_attrs,
            )
        elif parent_key:
            if self._word_present(parent_key, script_lower):
                self._check_entity_fields(
                    entity,
                    script_lower,
                    db_type,
                    missing_fields,
                    skipped_fields,
                    context=(
                        f"embedded in '{mapping.parent_entity}' "
                        f"(parent block not parsed, searched full script)"
                    ),
                    graph_fk_attrs=graph_fk_attrs,
                )
            else:
                missing_entities.append(
                    f"Parent entity '{mapping.parent_entity}' for embedded "
                    f"'{entity.name}' not found in script"
                )

    def _check_entity_fields(
        self,
        entity: Entity,
        block: str,
        db_type: DatabaseType,
        missing: list[str],
        skipped: list[str],
        context: str = "",
        graph_fk_attrs: frozenset[str] = frozenset(),
    ) -> None:
        block_lower = block.lower()
        ctx = f" ({context})" if context else ""

        for attr in entity.attributes:
            attr_lower = attr.name.lower()

            if db_type == DatabaseType.GRAPH and (
                not self._is_constrained(attr) or attr_lower in graph_fk_attrs
            ):
                skipped.append(f"{entity.name}.{attr.name}")
                continue

            if not self._word_present(attr_lower, block_lower):
                missing.append(
                    f"Entity '{entity.name}'{ctx}: attribute '{attr.name}' "
                    f"not found"
                )

    def _check_relationship_attributes(
        self,
        schema: LogicalSchema,
        script_lower: str,
        entity_blocks: dict[str, str],
        db_type: DatabaseType,
        missing: list[str],
    ) -> None:
        for rel in schema.relationships:
            if rel.cardinality != RelationshipCardinality.MANY_TO_MANY:
                continue
            if not rel.attributes:
                continue

            for attr in rel.attributes:
                if db_type == DatabaseType.GRAPH and not self._is_constrained(attr):
                    continue
                if not self._word_present(attr.name.lower(), script_lower):
                    missing.append(
                        f"Relationship '{rel.name}' "
                        f"({rel.source_entity} <-> {rel.target_entity}): "
                        f"attribute '{attr.name}' not found in script"
                    )

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _resolve_entity_block(
        self,
        entity_name: str,
        entity_key: str,
        entity_blocks: dict[str, str],
        db_type: DatabaseType,
    ) -> str | None:
        block = entity_blocks.get(entity_key)
        if block is not None:
            return block

        if db_type == DatabaseType.GRAPH:
            pascal = self._snake_to_pascal(entity_name).lower()
            return entity_blocks.get(pascal)

        return None

    @staticmethod
    def _get_graph_fk_attributes(
        entity_name: str, schema: LogicalSchema,
    ) -> frozenset[str]:
        fk_names: set[str] = set()
        entity = schema.get_entity(entity_name)
        if not entity:
            return frozenset()

        own_pks = {
            a.name.lower()
            for a in entity.attributes
            if a.constraints.is_primary_key
        }

        source_entities: set[str] = set()
        has_self_ref = False
        for rel in schema.relationships:
            if rel.target_entity == entity_name:
                source_entities.add(rel.source_entity.lower())
                if rel.source_entity == entity_name:
                    has_self_ref = True

        for attr in entity.attributes:
            attr_lower = attr.name.lower()
            if attr_lower in own_pks:
                continue
            for source in source_entities:
                if attr_lower == f"{source}_id":
                    fk_names.add(attr_lower)
                    break
            if has_self_ref and attr_lower.startswith("parent_"):
                fk_names.add(attr_lower)

        return frozenset(fk_names)

    @staticmethod
    def _word_present(word: str, text: str) -> bool:
        return bool(re.search(r"\b" + re.escape(word) + r"\b", text))

    @staticmethod
    def _is_constrained(attr: Attribute) -> bool:
        return (
            attr.constraints.is_primary_key
            or attr.constraints.is_unique
            or attr.constraints.is_indexed
        )

    @staticmethod
    def _snake_to_pascal(name: str) -> str:
        return "".join(word.capitalize() for word in name.split("_"))

    @staticmethod
    def _extract_paren_block(text: str, open_pos: int) -> str | None:
        if open_pos >= len(text) or text[open_pos] != "(":
            return None
        depth = 0
        for i in range(open_pos, len(text)):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    return text[open_pos + 1 : i]
        return None

    @staticmethod
    def _extract_brace_block(text: str, open_pos: int) -> str | None:
        if open_pos >= len(text) or text[open_pos] != "{":
            return None
        depth = 0
        for i in range(open_pos, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    return text[open_pos + 1 : i]
        return None
