package com.axnavigator.company;

import com.axnavigator.ax.calc.DataConfidence;

/**
 * process_metrics.data_source / ai_solution_catalog.data_source. 신뢰도 MEASURED > SURVEY = USER_INPUT > EXTRACTED > ASSUMED.
 * EXTRACTED는 문서에서 추출한 값을 사용자가 그대로 확정한 것, USER_INPUT은 사용자가 직접 입력하거나 고친 것이다.
 */
public enum DataSourceType {

	ASSUMED, SURVEY, MEASURED, EXTRACTED, USER_INPUT;

	public DataConfidence confidence() {
		return this == USER_INPUT ? DataConfidence.SURVEY : DataConfidence.valueOf(name());
	}

}
