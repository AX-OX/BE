package com.axnavigator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseConnectionVerifierTests {

	@Test
	void verifiesARealQueryWithoutRequiringDocker(CapturedOutput output) throws Exception {
		var dataSource = new DriverManagerDataSource("jdbc:h2:mem:connection-verifier", "sa", "");

		new DatabaseConnectionVerifier(dataSource).run(new DefaultApplicationArguments());

		assertThat(output.getOut()).contains(
			"Database connectivity verified: SELECT 1 returned 1 (database=H2)");
	}

}
