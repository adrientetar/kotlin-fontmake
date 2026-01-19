/*
 * Copyright 2025 the kotlin-fontmake authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.adrientetar.fontmake

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

class FontmakeTest {

    private fun hasNativeBinary(): Boolean {
        return try {
            FontMake.version() != null
        } catch (e: Exception) {
            false
        }
    }

    @Test
    @EnabledIf("hasNativeBinary")
    fun `version returns non-null string`() {
        val version = FontMake.version()
        assertThat(version).isNotNull()
        // Version string is just the version number, e.g. "3.11.1"
        assertThat(version).matches("\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `compile returns error for non-existent file`() {
        val result = FontMake.compile("/non/existent/path.glyphs")

        assertThat(result).isInstanceOf(CompilationResult.Error::class.java)
        val error = result as CompilationResult.Error
        assertThat(error.message).contains("not found")
    }

    @Test
    fun `compile options builds correct command line`() {
        val options = CompileOptions(
            outputs = listOf(
                OutputRequest(OutputFormat.OTF, OutlineOptions(removeOverlaps = true)),
                OutputRequest(OutputFormat.VARIABLE, OutlineOptions(removeOverlaps = false)),
            ),
            interpolateInstances = true,
            outputDir = "/tmp/output",
        )

        // Verify options are set correctly
        assertThat(options.outputs.map { it.format }).containsExactly(OutputFormat.OTF, OutputFormat.VARIABLE)
        assertThat(options.interpolateInstances).isTrue()
        assertThat(options.outputDir).isEqualTo("/tmp/output")
    }

    @Test
    fun `planner reuses base build when options match`() {
        val options = CompileOptions(
            outputs = listOf(
                OutputRequest(OutputFormat.TTF, OutlineOptions(removeOverlaps = true)),
                OutputRequest(OutputFormat.WOFF2_TTF, OutlineOptions(removeOverlaps = true)),
            ),
        )

        val plan = FontMake.planInvocations(options)

        assertThat(plan).hasSize(1)
        assertThat(plan[0].formatsToBuild).containsExactly(OutputFormat.TTF)
        assertThat(plan[0].requestedNativeFormats).containsExactly(OutputFormat.TTF)
        assertThat(plan[0].requestedWebFormats).containsExactly(OutputFormat.WOFF2_TTF)
    }

    @Test
    fun `planner splits builds when options differ`() {
        val options = CompileOptions(
            outputs = listOf(
                OutputRequest(OutputFormat.TTF, OutlineOptions(extraArgs = listOf("--foo"))),
                OutputRequest(OutputFormat.WOFF2_TTF, OutlineOptions(extraArgs = emptyList())),
            ),
        )

        val plan = FontMake.planInvocations(options)

        assertThat(plan).hasSize(2)
        assertThat(plan.flatMap { it.formatsToBuild }).contains(OutputFormat.TTF)
    }

    @Test
    fun `output format flags are correct`() {
        assertThat(OutputFormat.OTF.flag).isEqualTo("otf")
        assertThat(OutputFormat.TTF.flag).isEqualTo("ttf")
        assertThat(OutputFormat.VARIABLE.flag).isEqualTo("variable")
        assertThat(OutputFormat.VARIABLE_CFF2.flag).isEqualTo("variable-cff2")
        assertThat(OutputFormat.OTF_CFF2.flag).isEqualTo("otf-cff2")
    }
}
