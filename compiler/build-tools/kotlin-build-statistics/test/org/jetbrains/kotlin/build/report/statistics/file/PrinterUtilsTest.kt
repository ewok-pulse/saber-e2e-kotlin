/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.build.report.statistics.file

import org.jetbrains.kotlin.build.report.metrics.BuildAttribute
import org.jetbrains.kotlin.build.report.metrics.BuildPerformanceMetric
import org.jetbrains.kotlin.build.report.metrics.BuildTimeMetric
import org.jetbrains.kotlin.build.report.statistics.BuildStartParameters
import org.jetbrains.kotlin.build.report.statistics.CompileStatisticsData
import org.jetbrains.kotlin.build.report.statistics.StatTag
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

class PrinterUtilsTest {
    @Test
    fun testLargeCompileStatisticsData_KT85266() {
        val outputFile = File.createTempFile("build-report", ".txt")
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
                    outputFile, "repro", true
                )
                service.printBuildReport(data, outputFile)
                println("Completed without error. Output size: ${outputFile.length()} bytes")
            } catch (t: Throwable) {
                error = t
            }
        }, "repro-thread", 256 * 1024)
        thread.start()
        thread.join()
        error?.let { fail("${it.message}\n${it.stackTraceToString()}") }
        outputFile.deleteOnExit()
    }

}

private class StubCompileStatisticsData : CompileStatisticsData<BuildTimeMetric, BuildPerformanceMetric> {
    override fun getProjectName(): String = "repro"
    override fun getLabel(): String = ""
    override fun getTaskName(): String = ":compileKotlin"
    override fun getTaskResult(): String = "SUCCESS"
    override fun getStartTimeMs(): Long = 0L
    override fun getDurationMs(): Long = 100L
    override fun getTags(): Set<StatTag> = emptySet()
    override fun getChanges(): List<String> = emptyList()
    override fun getKotlinVersion(): String = "2.3.0"
    override fun getKotlinLanguageVersion(): String = "2.1"
    override fun getFinishTime(): Long = 0L
    override fun getCompilerArguments(): List<String> = emptyList()
    override fun getNonIncrementalAttributes(): Set<BuildAttribute> = setOf(BuildAttribute.UNKNOWN_CHANGES_IN_GRADLE_INPUTS)
    override fun getBuildTimesMetrics(): Map<BuildTimeMetric, Long> = emptyMap()
    override fun getPerformanceMetrics(): Map<BuildPerformanceMetric, Long> = emptyMap()
    override fun getGcTimeMetrics(): Map<String, Long> = emptyMap()
    override fun getGcCountMetrics(): Map<String, Long> = emptyMap()
    override fun getFromKotlinPlugin(): Boolean = true
    override fun getSkipMessage(): String = ""
    override fun getIcLogLines(): List<String> = emptyList()
}

