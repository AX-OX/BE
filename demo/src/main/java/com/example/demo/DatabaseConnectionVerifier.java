package com.example.demo;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class DatabaseConnectionVerifier implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionVerifier.class);

	private final DataSource dataSource;

	DatabaseConnectionVerifier(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		try (var connection = dataSource.getConnection();
			var statement = connection.createStatement()) {
			statement.setQueryTimeout(10);
			try (var result = statement.executeQuery("SELECT 1")) {
				if (!result.next() || result.getInt(1) != 1) {
					throw new IllegalStateException("Database connectivity check did not return 1");
				}
			}
			log.info("Database connectivity verified: SELECT 1 returned 1 (database={})",
				connection.getMetaData().getDatabaseProductName());
		}
	}

}
