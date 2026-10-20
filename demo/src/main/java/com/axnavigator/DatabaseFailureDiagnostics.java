package com.axnavigator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;

/** Preserve diagnostic types, stack traces and cause chains without logging credentials. */
final class DatabaseFailureDiagnostics {

	private static final String REDACTED = "[REDACTED]";
	private static final Pattern AUTHORIZATION = Pattern.compile("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+");
	private static final Pattern USER_INFO = Pattern.compile("(://)[^\\s/@]+@");
	private static final Pattern CREDENTIAL = Pattern.compile(
		"(?i)((?:[\"']?(?:password|passwd|pwd|access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|authorization|secret|private[_-]?key)[\"']?)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]]+)");
	private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
	private static final Pattern GOOGLE_TOKEN = Pattern.compile("\\bya29\\.[A-Za-z0-9._~-]+");
	private static final Pattern PRIVATE_KEY = Pattern.compile(
		"-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?-----END [^-]*PRIVATE KEY-----");

	private final List<String> secrets = new ArrayList<>();

	DatabaseFailureDiagnostics(Environment environment, HikariDataSource hikari) {
		for (String key : List.of("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password",
			"JWT_SECRET", "ax.jwt.secret", "INTERNAL_TOKEN", "ax.internal-token",
			"GOOGLE_OAUTH_ACCESS_TOKEN", "CLOUDSDK_AUTH_ACCESS_TOKEN")) {
			try {
				addSecret(environment.getProperty(key));
			}
			catch (IllegalArgumentException ignored) {
				// An unresolved optional placeholder must not hide the JDBC failure.
			}
		}
		if (hikari != null) {
			addSecret(hikari.getPassword());
			addSecret(hikari.getDataSourceProperties().getProperty("password"));
		}
		secrets.sort((left, right) -> Integer.compare(right.length(), left.length()));
	}

	private void addSecret(String value) {
		if (value != null && !value.isBlank()) {
			secrets.add(value);
			secrets.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
			secrets.add(value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
		}
	}

	String redact(String value) {
		if (value == null) {
			return "<unset>";
		}
		String safe = value;
		for (String secret : secrets) {
			safe = safe.replace(secret, REDACTED);
		}
		safe = PRIVATE_KEY.matcher(safe).replaceAll(REDACTED);
		safe = AUTHORIZATION.matcher(safe).replaceAll("$1" + REDACTED);
		safe = CREDENTIAL.matcher(safe).replaceAll("$1" + REDACTED);
		safe = USER_INFO.matcher(safe).replaceAll("$1" + REDACTED + "@");
		safe = JWT.matcher(safe).replaceAll(REDACTED);
		return GOOGLE_TOKEN.matcher(safe).replaceAll(REDACTED);
	}

	String safeJdbcUrl(String url) {
		// Log only the connection target, never arbitrary URL query parameters.
		return redact(url).split("[?;#]", 2)[0];
	}

	RuntimeException sanitize(Throwable failure) {
		return sanitize(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private RuntimeException sanitize(Throwable failure, Set<Throwable> visited) {
		if (!visited.add(failure)) {
			return new RuntimeException("[circular exception reference]");
		}
		String description = failure.getClass().getName();
		if (failure instanceof SQLException sql) {
			description += " [SQLState=" + sql.getSQLState() + ", errorCode=" + sql.getErrorCode() + "]";
		}
		description += ": " + failure.getMessage();
		RuntimeException safe = new RuntimeException(redact(description),
			failure.getCause() == null ? null : sanitize(failure.getCause(), visited));
		safe.setStackTrace(failure.getStackTrace());
		for (Throwable suppressed : failure.getSuppressed()) {
			safe.addSuppressed(sanitize(suppressed, visited));
		}
		if (failure instanceof SQLException sql && sql.getNextException() != null) {
			safe.addSuppressed(sanitize(sql.getNextException(), visited));
		}
		return safe;
	}

}
