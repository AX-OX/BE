package com.axnavigator.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.axnavigator.doc.DocumentUploadService;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.code().status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ex.code().name(), ex.getMessage(), ex.hint()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
		Map<String, Object> fields = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return validation("입력값이 올바르지 않습니다.", Map.of("fields", fields));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorResponse> handleInvalidParameter(HandlerMethodValidationException ex) {
		List<String> messages = ex.getAllErrors().stream().map(e -> e.getDefaultMessage()).toList();
		return validation("입력값이 올바르지 않습니다.", Map.of("errors", messages));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
			MultipartException.class })
	ResponseEntity<ErrorResponse> handleUnreadable(Exception ex) {
		return validation("요청 형식이 올바르지 않습니다.", Map.of());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex) {
		return validation("파일은 20MB 이하여야 합니다.", Map.of("maxBytes", DocumentUploadService.MAX_FILE_BYTES));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.of(ErrorCode.NOT_FOUND, "경로 " + ex.getResourcePath() + "는 없다"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity.status(ex.getStatusCode())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), "지원하지 않는 메서드: " + ex.getMethod(), Map.of()));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
		return ResponseEntity.status(ex.getStatusCode())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), "지원하지 않는 Content-Type", Map.of()));
	}

	private static ResponseEntity<ErrorResponse> validation(String message, Map<String, Object> hint) {
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), message, hint));
	}

	// 예상하지 못한 오류의 상세 정보는 로그에만 기록한다.
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "서버 내부 오류", Map.of()));
	}

}
