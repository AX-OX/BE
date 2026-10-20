package com.axnavigator.common;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 공통 오류 응답 { code, message, hint }로 변환되는 업무 예외. */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final Map<String, Object> hint;

	public ApiException(ErrorCode code, String message) {
		this(code, message, Map.of());
	}

	public ApiException(ErrorCode code, String message, Map<String, ?> hint) {
		super(message);
		this.code = code;
		this.hint = new LinkedHashMap<>(hint);
	}

	public static ApiException notFound(String message, Collection<?> validIds) {
		return new ApiException(ErrorCode.NOT_FOUND, message, Map.of("validIds", validIds));
	}

	public static ApiException validation(String message) {
		return new ApiException(ErrorCode.VALIDATION_ERROR, message);
	}

	public static ApiException validation(String message, Map<String, ?> hint) {
		return new ApiException(ErrorCode.VALIDATION_ERROR, message, hint);
	}

	public static ApiException invalidState(String message) {
		return new ApiException(ErrorCode.INVALID_STATE, message);
	}

	public static ApiException forbidden(String message) {
		return new ApiException(ErrorCode.FORBIDDEN, message);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> hint() {
		return hint;
	}

}
