from __future__ import annotations

from dbagnets.models.enums import ValidationStatus
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.validation import ValidationResult


class SchemaDepthChecker:

    @property
    def name(self) -> str:
        return "SchemaDepthChecker"

    def validate(self, schema: LogicalSchema) -> ValidationResult:
        adjacency: dict[str, list[str]] = {e.name: [] for e in schema.entities}

        for rel in schema.relationships:
            if rel.source_entity in adjacency:
                adjacency[rel.source_entity].append(rel.target_entity)

        chain_error = self._validate_depth_chain(schema, adjacency)
        if chain_error:
            return chain_error

        longest, path = self._find_longest_path_with_trace(adjacency)
        path_str = " -> ".join(path) if path else "(empty)"

        if longest == schema.depth:
            return ValidationResult(
                agent_name=self.name,
                status=ValidationStatus.PASS,
                feedback=f"Relationship depth is exactly {schema.depth}.",
                details=f"Longest path ({longest} hops): {path_str}",
            )

        if longest > schema.depth:
            fix_hint = (
                f"The longest directed path is {longest} hops but must be exactly "
                f"{schema.depth}. Remove or reverse relationships to shorten the "
                f"longest path. The extra segment is outside your depth_chain — "
                f"check that no additional 1:N relationships extend beyond the chain ends."
            )
        else:
            fix_hint = (
                f"The longest directed path is {longest} hops but must be exactly "
                f"{schema.depth}. Add entities and 1:N relationships to extend "
                f"the depth_chain to {schema.depth + 1} entities."
            )

        return ValidationResult(
            agent_name=self.name,
            status=ValidationStatus.FAIL,
            feedback=(
                f"Relationship depth is {longest}, expected {schema.depth}. "
                f"Current longest path: {path_str}. "
                f"{fix_hint}"
            ),
            details=(
                f"Longest path found: {longest} hops: {path_str}. "
                f"Required: exactly {schema.depth} hops."
            ),
        )

    def _validate_depth_chain(
        self, schema: LogicalSchema, adjacency: dict[str, list[str]]
    ) -> ValidationResult | None:
        chain = schema.depth_chain
        if not chain:
            return None

        expected_len = schema.depth + 1
        if len(chain) != expected_len:
            return ValidationResult(
                agent_name=self.name,
                status=ValidationStatus.FAIL,
                feedback=(
                    f"depth_chain has {len(chain)} entities, expected {expected_len} "
                    f"for depth={schema.depth}. "
                    f"Declared chain: {' -> '.join(chain)}."
                ),
                details=f"depth_chain must have exactly {expected_len} entity names.",
            )

        entity_names = set(schema.entity_names)
        for name in chain:
            if name not in entity_names:
                return ValidationResult(
                    agent_name=self.name,
                    status=ValidationStatus.FAIL,
                    feedback=(
                        f"depth_chain references entity '{name}' which does not exist. "
                        f"Declared chain: {' -> '.join(chain)}. "
                        f"Available entities: {', '.join(sorted(entity_names))}."
                    ),
                    details=f"Entity '{name}' not found in schema entities.",
                )

        for i in range(len(chain) - 1):
            src, tgt = chain[i], chain[i + 1]
            neighbors = adjacency.get(src, [])
            if tgt not in neighbors:
                return ValidationResult(
                    agent_name=self.name,
                    status=ValidationStatus.FAIL,
                    feedback=(
                        f"depth_chain declares {src} -> {tgt} but no directed "
                        f"relationship exists from '{src}' to '{tgt}'. "
                        f"Declared chain: {' -> '.join(chain)}. "
                        f"Fix: add a relationship with source_entity='{src}' "
                        f"and target_entity='{tgt}'."
                    ),
                    details=f"Missing relationship: {src} -> {tgt}.",
                )

        return None

    def _find_longest_path_with_trace(
        self, adjacency: dict[str, list[str]]
    ) -> tuple[int, list[str]]:
        if not adjacency:
            return 0, []

        best_length = 0
        best_path: list[str] = []

        def dfs(node: str, visited: set[str], path: list[str]) -> None:
            nonlocal best_length, best_path
            current_depth = len(path) - 1
            if current_depth > best_length:
                best_length = current_depth
                best_path = list(path)
            visited.add(node)
            for neighbor in adjacency.get(node, []):
                if neighbor not in visited:
                    path.append(neighbor)
                    dfs(neighbor, visited, path)
                    path.pop()
            visited.discard(node)

        for node in adjacency:
            dfs(node, set(), [node])

        return best_length, best_path
