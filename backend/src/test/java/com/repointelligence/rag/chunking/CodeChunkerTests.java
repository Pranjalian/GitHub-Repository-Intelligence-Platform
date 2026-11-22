package com.repointelligence.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CodeChunkerTests {

	private final CodeChunker chunker = new CodeChunker(150, 60, 10);

	private static SourceFile file(String path, String language, String text) {
		return new SourceFile(path, language, "SOURCE", ".", text);
	}

	@Nested
	class Java {

		static final String OWNER_CONTROLLER = """
				package org.example.owner;

				import java.util.List;
				import org.springframework.web.bind.annotation.GetMapping;

				/**
				 * Handles owner pages.
				 */
				@Controller
				class OwnerController {

					private final OwnerRepository owners;

					OwnerController(OwnerRepository owners) {
						this.owners = owners;
					}

					/** Finds owners by last name. */
					@GetMapping("/owners")
					public String processFindForm(String lastName) {
						List<Owner> results = owners.findByLastName(lastName);
						return results.isEmpty() ? "owners/find" : "owners/list";
					}

					static class Helper {

						int help() {
							return 1;
						}

					}

				}
				""";

		@Test
		void aClassIsSplitIntoAHeaderChunkAndOneChunkPerMethodWithCorrectMetadata() {
			List<ChunkDraft> chunks = chunker.chunk(file("src/main/java/org/example/owner/OwnerController.java", "Java",
					OWNER_CONTROLLER));

			ChunkDraft header = chunks.stream()
				.filter(c -> c.chunkType() == ChunkType.CLASS && c.className().equals("org.example.owner.OwnerController"))
				.findFirst()
				.orElseThrow();
			// the first top-level type starts at line 1, so package and imports are searchable
			assertThat(header.startLine()).isEqualTo(1);
			assertThat(header.endLine()).isEqualTo(33);
			assertThat(header.methodName()).isNull();
			assertThat(header.section()).isEqualTo("class OwnerController");
			assertThat(header.content()).contains("import org.springframework.web.bind.annotation.GetMapping;")
				.contains("Handles owner pages.")
				.contains("private final OwnerRepository owners;")
				.contains("// ... constructor OwnerController(OwnerRepository) (lines 14-16, a separate chunk)")
				.contains("// ... method processFindForm(String) (lines 18-23, a separate chunk)")
				.contains("// ... nested type Helper (lines 25-31, a separate chunk)")
				.doesNotContain("findByLastName(lastName)");

			ChunkDraft method = chunks.stream().filter(c -> "processFindForm".equals(c.methodName())).findFirst().orElseThrow();
			assertThat(method.chunkType()).isEqualTo(ChunkType.METHOD);
			assertThat(method.className()).isEqualTo("org.example.owner.OwnerController");
			assertThat(method.section()).isEqualTo("processFindForm(String)");
			// the Javadoc above the method belongs to it
			assertThat(method.startLine()).isEqualTo(18);
			assertThat(method.endLine()).isEqualTo(23);
			assertThat(method.content()).startsWith("\t/** Finds owners by last name. */").contains("owners.findByLastName(lastName)");
			assertThat(method.filePath()).isEqualTo("src/main/java/org/example/owner/OwnerController.java");
			assertThat(method.language()).isEqualTo("Java");
			assertThat(method.modulePath()).isEqualTo(".");

			ChunkDraft constructor = chunks.stream()
				.filter(c -> c.chunkType() == ChunkType.METHOD && "OwnerController".equals(c.methodName()))
				.findFirst()
				.orElseThrow();
			assertThat(constructor.section()).isEqualTo("OwnerController(OwnerRepository)");

			// nested types get their own class chunk and methods, named Outer.Inner
			assertThat(chunks).anySatisfy(c -> {
				assertThat(c.chunkType()).isEqualTo(ChunkType.CLASS);
				assertThat(c.className()).isEqualTo("org.example.owner.OwnerController.Helper");
			});
			assertThat(chunks).anySatisfy(c -> {
				assertThat(c.methodName()).isEqualTo("help");
				assertThat(c.className()).isEqualTo("org.example.owner.OwnerController.Helper");
			});
		}

		@Test
		void bodilessInterfaceMethodsStayInTheTypeChunk() {
			List<ChunkDraft> chunks = chunker.chunk(file("src/main/java/x/OwnerRepository.java", "Java", """
					package x;

					public interface OwnerRepository extends Repository<Owner, Integer> {

						Collection<Owner> findByLastName(String lastName);

						default int answer() {
							return 42;
						}

					}
					"""));

			assertThat(chunks).extracting(ChunkDraft::chunkType).containsExactly(ChunkType.CLASS, ChunkType.METHOD);
			assertThat(chunks.get(0).content()).contains("Collection<Owner> findByLastName(String lastName);");
			assertThat(chunks.get(0).section()).isEqualTo("interface OwnerRepository");
			assertThat(chunks.get(1).methodName()).isEqualTo("answer");
		}

		@Test
		void localClassesStayInsideTheirMethod() {
			List<ChunkDraft> chunks = chunker.chunk(file("A.java", "Java", """
					class A {
						void run() {
							class Local {
								void inner() {
								}
							}
						}
					}
					"""));

			assertThat(chunks).extracting(ChunkDraft::className).containsOnly("A");
			assertThat(chunks).filteredOn(c -> c.chunkType() == ChunkType.METHOD).extracting(ChunkDraft::methodName)
				.containsExactly("run");
		}

		@Test
		void aFileThatDoesNotParseFallsBackToLabelledLineWindows() {
			List<ChunkDraft> chunks = chunker.chunk(file("Broken.java", "Java", "class Broken { void x( }\n"));

			assertThat(chunks).singleElement().satisfies(c -> {
				assertThat(c.chunkType()).isEqualTo(ChunkType.LINE_WINDOW);
				assertThat(c.section()).contains("did not parse as Java");
			});
		}

		@Test
		void aLongMethodIsSplitIntoNumberedPartsWithExactLineRanges() {
			String body = IntStream.rangeClosed(1, 320).mapToObj(i -> "\t\tint v" + i + " = " + i + ";")
				.collect(Collectors.joining("\n"));
			String text = "class Big {\n\tvoid huge() {\n" + body + "\n\t}\n}\n";

			List<ChunkDraft> parts = chunker.chunk(file("Big.java", "Java", text))
				.stream()
				.filter(c -> c.chunkType() == ChunkType.METHOD)
				.toList();

			assertThat(parts).hasSize(3);
			assertThat(parts).extracting(ChunkDraft::section)
				.containsExactly("huge(), part 1 of 3", "huge(), part 2 of 3", "huge(), part 3 of 3");
			assertThat(parts.get(0).startLine()).isEqualTo(2);
			assertThat(parts.get(0).endLine()).isEqualTo(151);
			assertThat(parts.get(1).startLine()).isEqualTo(152);
			assertThat(parts.get(2).endLine()).isEqualTo(323);
			assertThat(parts).allSatisfy(p -> assertThat(p.content().split("\n")).hasSizeLessThanOrEqualTo(150));
		}

		@Test
		void chunkingIsDeterministic() {
			SourceFile source = file("src/main/java/org/example/owner/OwnerController.java", "Java", OWNER_CONTROLLER);

			assertThat(chunker.chunk(source)).isEqualTo(chunker.chunk(source));
		}

	}

	@Nested
	class Config {

		@Test
		void yamlIsSplitByTopLevelKeyWithTheCommentAboveAKey() {
			String yaml = """
					server:
					  port: 8080
					# database settings
					spring:
					  datasource:
					    url: jdbc:postgresql://localhost/db
					    username: app
					  jpa:
					    open-in-view: false
					    hibernate:
					      ddl-auto: validate
					    properties:
					      a: 1
					      b: 2
					      c: 3
					      d: 4
					      e: 5
					      f: 6
					      g: 7
					      h: 8
					      i: 9
					      j: 10
					      k: 11
					      l: 12
					      m: 13
					      n: 14
					      o: 15
					      p: 16
					      q: 17
					      r: 18
					      s: 19
					      t: 20
					logging:
					  level: INFO
					""";

			List<ChunkDraft> chunks = chunker.chunk(file("src/main/resources/application.yml", "YAML", yaml));

			assertThat(chunks).extracting(ChunkDraft::chunkType).containsOnly(ChunkType.CONFIG_SECTION);
			// server (2 lines) + spring (30 lines) exceed the 30-line merge limit, so every key stays a section
			assertThat(chunks).extracting(ChunkDraft::section).containsExactly("server", "spring", "logging");
			ChunkDraft spring = chunks.get(1);
			assertThat(spring.startLine()).isEqualTo(3);
			assertThat(spring.content()).startsWith("# database settings\nspring:");
			assertThat(chunks.get(2).startLine()).isEqualTo(33);
		}

		@Test
		void smallYamlSectionsAreMergedAndDocumentsAreLabelled() {
			String yaml = "a: 1\nb: 2\n---\nc: 3\n";

			List<ChunkDraft> chunks = chunker.chunk(file("x.yaml", "YAML", yaml));

			assertThat(chunks).singleElement().satisfies(c -> {
				assertThat(c.section()).isEqualTo("a, b, (document 2), c (document 2)");
				assertThat(c.startLine()).isEqualTo(1);
				assertThat(c.endLine()).isEqualTo(4);
			});
		}

		@Test
		void propertiesAreGroupedByTheirFirstKeySegment() {
			StringBuilder text = new StringBuilder("# app\n");
			for (int i = 0; i < 20; i++) {
				text.append("spring.datasource.p").append(i).append("=v\n");
			}
			for (int i = 0; i < 20; i++) {
				text.append("server.p").append(i).append("=v\n");
			}

			List<ChunkDraft> chunks = chunker.chunk(file("application.properties", "Properties", text.toString()));

			assertThat(chunks).extracting(ChunkDraft::section).containsExactly("spring", "server");
			assertThat(chunks.get(0).startLine()).isEqualTo(1);
			assertThat(chunks.get(0).endLine()).isEqualTo(21);
			assertThat(chunks.get(1).startLine()).isEqualTo(22);
		}

	}

	@Nested
	class Docs {

		@Test
		void markdownIsSplitByHeadingWithTheHeadingPathAndFencedCodeIsNotAHeading() {
			String md = """
					Intro text.

					# Setup
					Install it.
					## Docker
					```sh
					# not a heading
					docker compose up
					```
					# Usage
					Run it.
					""";

			List<ChunkDraft> chunks = chunker.chunk(file("README.md", "Markdown", md));

			assertThat(chunks).extracting(ChunkDraft::chunkType).containsOnly(ChunkType.DOC_SECTION);
			assertThat(chunks).extracting(ChunkDraft::section)
				.containsExactly("(introduction)", "Setup", "Setup > Docker", "Usage");
			assertThat(chunks.get(2).content()).contains("# not a heading");
			assertThat(chunks.get(3).startLine()).isEqualTo(10);
		}

	}

	@Nested
	class Other {

		@Test
		void otherLanguagesGetOverlappingLineWindowsLabelledAsSuch() {
			String js = IntStream.rangeClosed(1, 130).mapToObj(i -> "const v" + i + " = " + i + ";")
				.collect(Collectors.joining("\n"));

			List<ChunkDraft> chunks = chunker.chunk(file("web/app.js", "JavaScript", js));

			assertThat(chunks).extracting(ChunkDraft::chunkType).containsOnly(ChunkType.LINE_WINDOW);
			assertThat(chunks).extracting(c -> c.startLine() + "-" + c.endLine()).containsExactly("1-60", "51-110", "101-130");
			assertThat(chunks.get(0).section()).isEqualTo("line window, lines 1-60");
		}

		@Test
		void blankFilesHaveNoChunks() {
			assertThat(chunker.chunk(file("empty.txt", null, "\n\n  \n"))).isEmpty();
		}

		@Test
		void theIndexedTextCarriesTheLocationAndNames() {
			ChunkDraft draft = new ChunkDraft("a/B.java", "Java", "SOURCE", ".", "a.B", "run", ChunkType.METHOD, "run()", 3,
					5, "void run() {}");

			assertThat(draft.indexedText()).isEqualTo(
					"File: a/B.java (Java)\nKind: METHOD\nType: a.B\nMember: run\nSection: run()\n\nvoid run() {}");
		}

	}

}
