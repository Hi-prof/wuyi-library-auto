from __future__ import annotations

from http import HTTPStatus


class ApiRequestError(Exception):
    def __init__(
        self, message: str, status_code: HTTPStatus = HTTPStatus.BAD_REQUEST
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
