package com.axnavigator.ax.calc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.axnavigator.ax.calc.CalcInputs.CompanyInput;
import com.axnavigator.ax.calc.CalcInputs.LinkInput;
import com.axnavigator.ax.calc.CalcInputs.MetricInput;
import com.axnavigator.ax.calc.CalcInputs.ProcessInput;
import com.axnavigator.ax.calc.CalcInputs.SolutionInput;
import com.axnavigator.ax.calc.CalcInputs.SystemInput;

/** test resources ax/seed/*.csv (데이터 패키지 v3)를 읽어 계산 입력을 만든다. DB 없이 계산 모듈을 검증하기 위한 것이다. */
final class SeedCsv {

	private SeedCsv() {
	}

	static CompanyInput company(long companyId) {
		Map<String, String> company = table("companies").stream()
			.filter(r -> Long.parseLong(r.get("company_id")) == companyId)
			.findFirst()
			.orElseThrow();
		Set<Long> departmentIds = table("departments").stream()
			.filter(r -> Long.parseLong(r.get("company_id")) == companyId)
			.map(r -> Long.parseLong(r.get("department_id")))
			.collect(Collectors.toSet());
		List<ProcessInput> processes = table("business_processes").stream()
			.filter(r -> departmentIds.contains(Long.parseLong(r.get("department_id"))))
			.map(r -> new ProcessInput(Long.parseLong(r.get("process_id")), Long.parseLong(r.get("department_id")),
					decimal(r.get("standardization_level")), bool(r.get("requires_approval")),
					r.get("work_type").isEmpty() ? null : r.get("work_type")))
			.toList();
		Set<Long> processIds = processes.stream().map(ProcessInput::processId).collect(Collectors.toSet());
		List<MetricInput> metrics = table("process_metrics").stream()
			.filter(r -> processIds.contains(Long.parseLong(r.get("process_id"))))
			.map(r -> new MetricInput(Long.parseLong(r.get("metric_id")), Long.parseLong(r.get("process_id")),
					LocalDate.parse(r.get("period_start")), LocalDate.parse(r.get("period_end")),
					Integer.parseInt(r.get("processing_count")), decimal(r.get("avg_processing_minutes")),
					decimal(r.get("error_rate")), Integer.parseInt(r.get("hourly_labor_cost_krw")),
					DataConfidence.valueOf(r.get("data_source"))))
			.toList();
		List<SystemInput> systems = table("it_systems").stream()
			.filter(r -> Long.parseLong(r.get("company_id")) == companyId)
			.map(r -> new SystemInput(Long.parseLong(r.get("system_id")), bool(r.get("api_available"))))
			.toList();
		List<LinkInput> links = table("process_systems").stream()
			.filter(r -> processIds.contains(Long.parseLong(r.get("process_id"))))
			.map(r -> new LinkInput(Long.parseLong(r.get("process_id")), Long.parseLong(r.get("system_id"))))
			.toList();
		List<SolutionInput> solutions = table("ai_solution_catalog").stream()
			.map(r -> new SolutionInput(Long.parseLong(r.get("solution_id")), r.get("work_type"),
					r.get("depends_on_solution_id").isEmpty() ? null : Long.parseLong(r.get("depends_on_solution_id")),
					Long.parseLong(r.get("base_build_cost_krw")), Long.parseLong(r.get("monthly_run_cost_krw")),
					decimal(r.get("automation_rate_low")), decimal(r.get("automation_rate_base")),
					decimal(r.get("automation_rate_high")), decimal(r.get("time_reduction_rate_low")),
					decimal(r.get("time_reduction_rate_base")), decimal(r.get("time_reduction_rate_high")),
					decimal(r.get("min_standardization_level")), bool(r.get("requires_api"))))
			.toList();
		return new CompanyInput(companyId, Long.parseLong(company.get("ax_budget_krw")), processes, metrics, systems,
				links, solutions);
	}

	private static BigDecimal decimal(String value) {
		return value == null || value.isEmpty() ? null : new BigDecimal(value);
	}

	private static boolean bool(String value) {
		return "true".equalsIgnoreCase(value) || "1".equals(value);
	}

	static List<Map<String, String>> table(String name) {
		try (InputStream in = SeedCsv.class.getResourceAsStream("/ax/seed/" + name + ".csv")) {
			if (in == null) {
				throw new IllegalStateException("missing seed csv: " + name);
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) {
				text = text.substring(1);
			}
			List<List<String>> rows = parse(text);
			List<String> header = rows.get(0);
			List<Map<String, String>> result = new ArrayList<>();
			for (List<String> row : rows.subList(1, rows.size())) {
				Map<String, String> map = new LinkedHashMap<>();
				for (int i = 0; i < header.size(); i++) {
					map.put(header.get(i), i < row.size() ? row.get(i) : "");
				}
				result.add(map);
			}
			return result;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** RFC 4180 CSV (따옴표 안의 쉼표·줄바꿈·"" 이스케이프). */
	private static List<List<String>> parse(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					field.append('"');
					i++;
				}
				else if (c == '"') {
					quoted = false;
				}
				else {
					field.append(c);
				}
			}
			else if (c == '"') {
				quoted = true;
			}
			else if (c == ',') {
				row.add(field.toString());
				field.setLength(0);
			}
			else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
				row.add(field.toString());
				field.setLength(0);
				rows.add(row);
				row = new ArrayList<>();
			}
			else {
				field.append(c);
			}
		}
		if (field.length() > 0 || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row);
		}
		return rows;
	}

}
