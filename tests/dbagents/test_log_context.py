from __future__ import annotations

import logging

from dbagnets.log_context import LogContextFilter, clear_log_context, set_log_context


class TestSetLogContext:
    def test_sets_context_used_by_filter(self):
        filt = LogContextFilter()
        record = logging.LogRecord("test", logging.INFO, "", 0, "msg", (), None)

        set_log_context("postgresql")
        filt.filter(record)

        assert record.ctx == "postgresql"

    def test_default_context_is_main(self):
        filt = LogContextFilter()
        record = logging.LogRecord("test", logging.INFO, "", 0, "msg", (), None)

        clear_log_context()
        filt.filter(record)

        assert record.ctx == "main"


class TestClearLogContext:
    def test_resets_to_main(self):
        filt = LogContextFilter()

        set_log_context("neo4j")
        clear_log_context()

        record = logging.LogRecord("test", logging.INFO, "", 0, "msg", (), None)
        filt.filter(record)
        assert record.ctx == "main"


class TestLogContextFilter:
    def test_always_returns_true(self):
        filt = LogContextFilter()
        record = logging.LogRecord("test", logging.INFO, "", 0, "msg", (), None)
        assert filt.filter(record) is True
