package com.wuyi.libraryauto.core.runtime.network

import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

object NetworkRecoveryEventBus {
    private val flow = MutableSharedFlow<TriggerSource>(replay = 0, extraBufferCapacity = 8)

    fun events(): SharedFlow<TriggerSource> = flow.asSharedFlow()

    @OptIn(FlowPreview::class)
    fun mergedEvents(
        debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
        sampleMillis: Long = DEFAULT_SAMPLE_MILLIS,
    ): Flow<TriggerSource> =
        events()
            .debounce(debounceMillis)
            .distinctUntilChanged()
            .sample(sampleMillis)

    suspend fun emit(source: TriggerSource) {
        flow.emit(source)
    }

    fun tryEmit(source: TriggerSource): Boolean = flow.tryEmit(source)

    private const val DEFAULT_DEBOUNCE_MILLIS = 5_000L
    private const val DEFAULT_SAMPLE_MILLIS = 30_000L
}
