package com.axnavigator.company;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 한 기업의 업무 데이터 스냅샷 (조회 API와 계산 입력이 함께 쓴다). */
public record CompanyData(Company company, List<Department> departments, List<BusinessProcess> processes,
		List<ProcessMetric> metrics, List<ItSystem> systems, List<ProcessSystem> links, List<AiSolution> solutions) {

	public Map<Long, Department> departmentsById() {
		return departments.stream().collect(Collectors.toMap(Department::getDepartmentId, Function.identity()));
	}

}
