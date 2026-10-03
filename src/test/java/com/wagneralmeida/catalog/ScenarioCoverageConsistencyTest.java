package com.wagneralmeida.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ScenarioCoverageConsistencyTest {

    private static final Pattern SPEC_SCENARIO = Pattern.compile("(?m)^\\| (C\\d+) \\|");
    private static final Pattern TEST_METHOD = Pattern.compile("\\bvoid\\s+c0*(\\d+)(?:_[A-Za-z0-9_]+)");

    @Test
    void everyScenarioInSectionEightAppearsInATestMethodName() throws IOException {
        String spec = Files.readString(Path.of("docs/spec.md"), StandardCharsets.UTF_8);
        String sectionEight = between(spec, "## 8. Acceptance scenarios", "## 9. Non-functional requirements");
        Set<String> required = matches(SPEC_SCENARIO, sectionEight, matcher -> matcher.group(1));

        String testSources;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            testSources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(ScenarioCoverageConsistencyTest::read)
                    .collect(Collectors.joining("\n"));
        }
        Set<String> covered = matches(
                TEST_METHOD,
                testSources,
                matcher -> "C" + Integer.parseInt(matcher.group(1)));

        assertThat(required).containsExactlyInAnyOrderElementsOf(covered);
        assertThat(required).containsExactlyInAnyOrder(
                "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11",
                "C12", "C13", "C14", "C15", "C16", "C17", "C18", "C19", "C20", "C21");
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).isNotNegative();
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private Set<String> matches(Pattern pattern, String source, MatchValue value) {
        Matcher matcher = pattern.matcher(source);
        java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            matches.add(value.from(matcher));
        }
        return Set.copyOf(matches);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test source", exception);
        }
    }

    @FunctionalInterface
    private interface MatchValue {
        String from(Matcher matcher);
    }
}
