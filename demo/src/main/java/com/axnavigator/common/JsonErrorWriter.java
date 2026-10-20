package com.axnavigator.common;

import java.io.IOException;

import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 필터 단계(컨트롤러 밖)에서 공통 오류 형식으로 응답을 쓴다. */
public final class JsonErrorWriter {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private JsonErrorWriter() {
	}

	public static void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
		response.setStatus(code.status().value());
		response.setCharacterEncoding("UTF-8");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		MAPPER.writeValue(response.getOutputStream(), ErrorResponse.of(code, message));
	}

}
