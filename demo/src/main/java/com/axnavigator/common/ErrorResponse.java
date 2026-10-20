package com.axnavigator.common;

import java.util.Map;

/** 기준 문서 §7 공통 오류 응답 형식. */
public record ErrorResponse(String code, String message, Map<String, Object> hint) {

	public static ErrorResponse of(ErrorCode code, String message) {
		return new ErrorResponse(code.name(), message, Map.of());
	}

}
