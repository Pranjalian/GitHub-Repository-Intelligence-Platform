package com.repointelligence.support;

import java.util.List;

/**
 * The "security-smells" fixture: fake secrets across Java, YAML, .env and JS files, plus
 * Semgrep-detectable Java. Every secret is assembled at runtime from pieces, so no token-shaped literal is
 * ever committed to the repository (it would trip push protection and secret scanners).
 */
public final class SecurityFixture {

	private SecurityFixture() {
	}

	private static final String ALNUM_36 = "abcdefghijklmnopqrstuvwxyz0123456789";

	public static final String AWS_KEY = "AK" + "IA" + "QWERTYUIOP123456";

	public static final String GITHUB_TOKEN = "gh" + "p_" + ALNUM_36;

	public static final String SLACK_TOKEN = "xo" + "xb-" + "123456789012-abcdefghijklmnop";

	public static final String STRIPE_KEY = "sk" + "_li" + "ve_" + "abcdefghijklmnopqrstuvwx";

	public static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + "." + "eyJ" + "zdWIiOiIxMjM0NTY3ODkwIn0" + "."
			+ "abcdefghijklmnopqrstuvwxyz0123";

	public static final String JAVA_PASSWORD = "Tr0ub4dor" + "&3xK9z";

	public static final String YAML_PASSWORD = "s3cr3t" + "Val!ue99x";

	public static final String ENV_TOKEN = "9fQ2xLm8" + "ZpR4tVb7";

	public static final String JS_API_KEY = "k3Yv4lU3-" + "Zx9Qm2Lp8Rt";

	public static final String PRIVATE_KEY_HEADER = "-----BEGIN " + "RSA PRIVATE KEY-----";

	/** Every raw value that must never appear in a stored, logged or returned finding. */
	public static final List<String> ALL_SECRETS = List.of(AWS_KEY, GITHUB_TOKEN, SLACK_TOKEN, STRIPE_KEY, JWT,
			JAVA_PASSWORD, YAML_PASSWORD, ENV_TOKEN, JS_API_KEY);

	public static TarGzBuilder tarball() {
		return new TarGzBuilder()
			.file("pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
					+ "<groupId>g</groupId><artifactId>smells</artifactId><version>1</version></project>")
			.file("src/main/java/demo/Config.java", "package demo;\n\npublic class Config {\n"
					+ "\tstatic final String AWS = \"" + AWS_KEY + "\";\n"
					+ "\tstatic final String GH = \"" + GITHUB_TOKEN + "\";\n"
					+ "\tString password = \"" + JAVA_PASSWORD + "\";\n"
					+ "\tString placeholder = \"changeme\";\n"
					+ "}\n")
			.file("src/main/java/demo/Risky.java", "package demo;\n\nimport java.io.InputStream;\nimport java.io.ObjectInputStream;\n"
					+ "import java.security.MessageDigest;\nimport java.sql.Statement;\n\npublic class Risky {\n"
					+ "\tObject load(InputStream in) throws Exception {\n\t\treturn new ObjectInputStream(in).readObject();\n\t}\n"
					+ "\tbyte[] hash(byte[] data) throws Exception {\n\t\treturn MessageDigest.getInstance(\"MD5\").digest(data);\n\t}\n"
					+ "\tvoid query(Statement st, String name) throws Exception {\n\t\tst.executeQuery(\"select * from users where name = '\" + name + \"'\");\n\t}\n"
					+ "}\n")
			.file("src/main/resources/application.yml", "spring:\n  datasource:\n    password: " + YAML_PASSWORD + "\n"
					+ "other:\n  token: ${TOKEN}\n  secret: changeme\n")
			.file(".env", "API_TOKEN=" + ENV_TOKEN + "\nSLACK=" + SLACK_TOKEN + "\nEMPTY_PASSWORD=\n")
			.file("web/app.js", "const apiKey = \"" + JS_API_KEY + "\";\nconst stripe = \"" + STRIPE_KEY + "\";\n"
					+ "const jwt = \"" + JWT + "\";\nconst fake = \"xxxxxxxxxxxx\";\n")
			.file("keys/id_rsa.txt", PRIVATE_KEY_HEADER + "\nnot-a-real-key-body\n-----END RSA PRIVATE KEY-----\n");
	}

}
