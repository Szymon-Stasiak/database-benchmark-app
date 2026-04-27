from __future__ import annotations

from dbagnets.models import LoopState


class TestLoopState:
    def test_has_correct_default_values(self, sample_config):
        state = LoopState(config=sample_config)
        assert state.max_iterations == 10
        assert state.current_iteration == 0
        assert state.history == []
        assert state.final_script is None
        assert state.success is False
