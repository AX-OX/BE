package com.axnavigator.common;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "상태 확인", description = "애플리케이션 실행 상태 확인")
@RestController
public class HealthController {

	@Operation(summary = "서버 상태 확인", description = "인증 없이 서버의 실행 상태를 확인합니다. 정상 응답은 {\"status\":\"UP\"}이며 DB나 외부 서비스 연결 상태를 검사하지 않습니다.")
	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}

}
