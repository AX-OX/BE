/**
 * AX점수·보조 ROI·포트폴리오 순수 계산 모듈 (기준 문서 §6). Spring·DB에 의존하지 않는다.
 * <p>
 * 계산은 BigDecimal, 반올림은 HALF_UP. 중간값(가중 인건비, 연간 시간, 절감 시간, Impact, Feasibility)은
 * 반올림하지 않고 응답 값을 만들 때만 반올림한다. 포트폴리오 목표는 반올림된 AX점수의 합이다.
 */
package com.axnavigator.ax.calc;
