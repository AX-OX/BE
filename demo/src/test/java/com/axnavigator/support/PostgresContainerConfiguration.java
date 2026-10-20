package com.axnavigator.support;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** db/init의 00·01·03을 /docker-entrypoint-initdb.d에 넣어 기동한다. 추가 SQL(fixture)은 시스템 프로퍼티로 고르지 않고 하위 클래스에서 지정한다. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

	static final Path DB_DIR = Path.of("..", "db").toAbsolutePath().normalize();

	static final List<String> INIT_SCRIPTS = List.of("init/00_schema_postgres16.sql",
			"init/01_seed_input_postgres.sql", "init/03_pgvector.sql");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return createContainer(List.of());
	}

	public static PostgreSQLContainer createContainer(List<String> extraScripts) {
		PostgreSQLContainer container = new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:0.8.1-pg16").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("ax")
			.withUsername("ax")
			.withPassword("ax");
		int order = 0;
		for (String script : concat(INIT_SCRIPTS, extraScripts)) {
			String target = String.format("/docker-entrypoint-initdb.d/%02d_%s", order++,
					Path.of(script).getFileName());
			container.withCopyFileToContainer(MountableFile.forHostPath(DB_DIR.resolve(script)), target);
		}
		return container;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
	}

}
