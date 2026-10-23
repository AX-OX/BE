package com.axnavigator.ax.calc;

/** 데이터 신뢰도. 선언 순서가 낮은 등급부터다 (ASSUMED < EXTRACTED < SURVEY < MEASURED). 점수에 넣지 않고 표시만 한다. */
public enum DataConfidence {

	ASSUMED, EXTRACTED, SURVEY, MEASURED

}
