package com.axnavigator.common;

import org.springframework.http.HttpStatus;

/** 기준 문서 §7 오류 코드. INVALID_CREDENTIALS는 로그인 실패 전용(§8, 401). */
public enum ErrorCode {

	NOT_FOUND(HttpStatus.NOT_FOUND),
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
	EXCLUDED_PROCESS(HttpStatus.BAD_REQUEST),
	INSUFFICIENT_DATA(HttpStatus.BAD_REQUEST),
	CALCULATION_MISMATCH(HttpStatus.BAD_REQUEST),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT),
	INVALID_STATE(HttpStatus.CONFLICT),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
