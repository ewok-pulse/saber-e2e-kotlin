/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.build.report.statistics.file

import org.jetbrains.kotlin.build.report.metrics.BuildAttribute
import org.jetbrains.kotlin.build.report.metrics.BuildPerformanceMetric
import org.jetbrains.kotlin.build.report.metrics.BuildTimeMetric
import org.jetbrains.kotlin.build.report.metrics.COMPILATION_ROUND
import org.jetbrains.kotlin.build.report.metrics.RUN_COMPILATION_IN_WORKER
import org.jetbrains.kotlin.build.report.statistics.BuildStartParameters
import org.jetbrains.kotlin.build.report.statistics.CompileStatisticsData
import org.jetbrains.kotlin.build.report.statistics.StatTag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

class PrinterUtilsTest {
    @Test
    fun testLargeCompileStatisticsDataWithLimitedStackSize_KT85266(@TempDir tempDir: File) {
        val threadStackSize: Long = 256 * 1024
        val outputFile = tempDir.resolve("build-repor.txt")
        var error: Throwable? = null
        val thread = Thread(null, {
            try {
                val statisticsData = (0 until 100_000).map { StubCompileStatisticsData() }

                val startParameters = BuildStartParameters(
                    tasks = listOf(""),
                )

                val data = ReadableFileReportData(
                    statisticsData = statisticsData,
                    startParameters = startParameters,
                )

                val service = ReadableFileReportService<BuildTimeMetric, BuildPerformanceMetric>(
                    outputFile, "project", true
                )
                service.printBuildReport(data, outputFile)
            } catch (t: Throwable) {
                error = t
            }
        }, "new-thread", threadStackSize)
        thread.start()
        thread.join()
        error?.let { fail("${it.message}\n${it.stackTraceToString()}") }
    }

}

private class StubCompileStatisticsData : CompileStatisticsData<BuildTimeMetric, BuildPerformanceMetric> {
    override fun getProjectName(): String = "project"
    override fun getLabel(): String = ""
    override fun getTaskName(): String = ":compileKotlin"
    override fun getTaskResult(): String = "SUCCESS"
    override fun getStartTimeMs(): Long = 0L
    override fun getDurationMs(): Long = 0L
    override fun getTags(): Set<StatTag> = emptySet()
    override fun getChanges(): List<String> = emptyList()
    override fun getKotlinVersion(): String = "version"
    override fun getKotlinLanguageVersion(): String = "language.version"
    override fun getFinishTime(): Long = 0L
    override fun getCompilerArguments(): List<String> = emptyList()
    override fun getNonIncrementalAttributes(): Set<BuildAttribute> = setOf(BuildAttribute.UNKNOWN_CHANGES_IN_GRADLE_INPUTS)
    override fun getBuildTimesMetrics(): Map<BuildTimeMetric, Long> = mapOf(RUN_COMPILATION_IN_WORKER to 1)
    override fun getPerformanceMetrics(): Map<BuildPerformanceMetric, Long> = mapOf(COMPILATION_ROUND to 1)
    override fun getGcTimeMetrics(): Map<String, Long> = mapOf("time" to 1)
    override fun getGcCountMetrics(): Map<String, Long> = mapOf("count" to 1)
    override fun getFromKotlinPlugin(): Boolean = true
    override fun getSkipMessage(): String = ""
    override fun getIcLogLines(): List<String> = emptyList()
}

