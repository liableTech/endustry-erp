package com.endustry.erp.reporting;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportingController {

	private final DashboardService dashboard;
	private final ReportService reports;

	@GetMapping("/dashboard/summary")
	public DashboardService.Summary summary() {
		return dashboard.summary();
	}

	@GetMapping("/reports")
	public List<ReportService.Definition> definitions() {
		return reports.definitions();
	}

	@GetMapping("/reports/{key}")
	public ReportService.Report run(@PathVariable String key,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return reports.run(key, from, to);
	}
}
