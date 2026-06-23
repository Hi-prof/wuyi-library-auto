package com.wuyi.libraryauto.core.runtime.diagnostics

import android.content.Context

object LocalDiagnosticLogger {
    @Volatile
    private var repository: LocalDiagnosticLogRepository? = null

    fun install(context: Context) {
        repository = LocalDiagnosticLogRepository(context.applicationContext)
    }

    fun info(
        source: String,
        title: String,
        detailLines: List<String> = emptyList(),
    ) {
        append("INFO", source, title, detailLines)
    }

    fun warn(
        source: String,
        title: String,
        detailLines: List<String> = emptyList(),
    ) {
        append("WARN", source, title, detailLines)
    }

    fun error(
        source: String,
        title: String,
        detailLines: List<String> = emptyList(),
    ) {
        append("ERROR", source, title, detailLines)
    }

    fun repositoryOrNull(): LocalDiagnosticLogRepository? = repository

    private fun append(
        level: String,
        source: String,
        title: String,
        detailLines: List<String>,
    ) {
        repository?.append(
            level = level,
            source = source,
            title = title,
            detailLines = detailLines,
        )
    }
}
