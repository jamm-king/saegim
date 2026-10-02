package com.saegim

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse

class DependencyRulesTest {
    @Test fun `core has no dependency on web persistence provider or Spring`() {
        for (layer in listOf("domain", "application")) {
            Files.walk(Path.of("src/main/kotlin/com/saegim", layer)).use { files ->
                files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    val source = Files.readString(file)
                    for (forbidden in listOf("org.springframework", "reactor.", "tools.jackson", "com.fasterxml", "jakarta.", "com.saegim.adapter", "com.saegim.configuration")) {
                        assertFalse(source.contains(forbidden), "$file depends on $forbidden")
                    }
                    if (layer == "domain") assertFalse(source.contains("com.saegim.application"), "$file depends on application")
                }
            }
        }
    }
}
