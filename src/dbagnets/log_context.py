from __future__ import annotations

import logging
import threading

_local = threading.local()


def set_log_context(context: str) -> None:
    _local.context = context


def clear_log_context() -> None:
    _local.context = None


class LogContextFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.ctx = getattr(_local, "context", None) or "main"
        return True
