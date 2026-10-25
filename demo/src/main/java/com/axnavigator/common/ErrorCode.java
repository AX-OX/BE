package com.axnavigator.common;

import org.springframework.http.HttpStatus;

/** 공통 API 오류 코드. 로그인 실패와 유효하지 않은 인증은 UNAUTHORIZED로 통일한다. */
public enum ErrorCode {

	NOT_FOUND(HttpStatus.NOT_FOUND),
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
	EXCLUDED_PROCESS(HttpStatus.BAD_REQUEST),
	INSUFFICIENT_DATA(HttpStatus.BAD_REQUEST),
	CALCULATION_MISMATCH(HttpStatus.BAD_REQUEST),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT),
	DUPLICATE_DOCUMENT(HttpStatus.CONFLICT),
	INVALID_STATE(HttpStatus.CONFLICT),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN),
	AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
