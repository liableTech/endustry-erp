package com.endustry.erp.platform.settings;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public final class SettingsRepositories {

	private SettingsRepositories() {
	}

	public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Long> {
	}

	public interface ThemeSettingsRepository extends JpaRepository<ThemeSettings, Long> {
	}

	public interface ParameterRepository extends JpaRepository<Parameter, Long> {
		Optional<Parameter> findByParamKey(String paramKey);
	}

	public interface NumberSeriesRepository extends JpaRepository<NumberSeries, Long> {
		@Lock(LockModeType.PESSIMISTIC_WRITE)
		Optional<NumberSeries> findBySeriesKey(String seriesKey);
	}
}
