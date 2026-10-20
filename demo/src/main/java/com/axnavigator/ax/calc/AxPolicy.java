package com.axnavigator.ax.calc;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ax_score_policy_v1.json 값. 코드에 가중치·배점·제외 업무를 하드코딩하지 않는다.
 *
 * @param exclusions 제외 업무 processId → 사유
 */
public record AxPolicy(String version, BigDecimal impactWeight, BigDecimal feasibilityWeight,
		BigDecimal standardizationMultiplier, BigDecimal apiOkPoints, int scoreScale, int maxItems,
		Map<Long, String> exclusions) {

	public static AxPolicy read(InputStream json) throws IOException {
		JsonNode root = JsonMapper.builder().build().readTree(json);
		Map<Long, String> exclusions = new LinkedHashMap<>();
		for (JsonNode e : root.path("exclusions")) {
			exclusions.put(e.path("process_id").asLong(), e.path("reason").asString());
		}
		return new AxPolicy(root.path("version").asString(), decimal(root.path("weights").path("impact")),
				decimal(root.path("weights").path("feasibility")),
				decimal(root.path("feasibility").path("standardization_multiplier")),
				decimal(root.path("feasibility").path("api_ok_points")), root.path("rounding").asInt(),
				root.path("portfolio").path("max_items").asInt(), Collections.unmodifiableMap(exclusions));
	}

	private static BigDecimal decimal(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			throw new IllegalStateException("ax_score_policy 값 누락: " + node);
		}
		// 부동소수 오차 없이 JSON 숫자 텍스트 그대로 사용
		return new BigDecimal(node.asString());
	}

}
