package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.storage.audit.BeaconScanAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.db.BeaconScanAuditEntity
import com.wuyi.libraryauto.core.storage.db.SignInAuditEntity
import com.wuyi.libraryauto.core.storage.db.SignInErrorCount
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SignInMonitoringViewModel(
    private val source: SignInMonitoringDataSource,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : ViewModel() {
    var uiState by mutableStateOf(SignInMonitoringUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            uiState = SignInMonitoringUiState(isLoading = true)
            val snapshot: Deferred<SignInMonitoringSnapshot> =
                async {
                    val now = nowEpochSeconds()
                    source.load(rangeStartEpochSeconds = now - SECONDS_PER_DAY, rangeEndEpochSeconds = now)
                }
            val loadedWithinPlaceholderWindow =
                withTimeoutOrNull(PLACEHOLDER_DELAY_MILLIS) {
                    snapshot.await()
                }
            val loaded =
                if (loadedWithinPlaceholderWindow != null) {
                    loadedWithinPlaceholderWindow
                } else {
                uiState = uiState.copy(showPlaceholder = true)
                    snapshot.await()
            }
            uiState =
                SignInMonitoringUiState(
                    isLoading = false,
                    showPlaceholder = false,
                    signInAudits = loaded.signInAudits.map(SignInAuditDisplay::masked),
                    beaconScanAudits = loaded.beaconScanAudits,
                    errorAggregates = loaded.errorAggregates,
                    emptyMessage =
                        if (loaded.signInAudits.isEmpty() && loaded.beaconScanAudits.isEmpty()) {
                            "暂无记录"
                        } else {
                            ""
                        },
                )
        }
    }

    private companion object {
        private const val PLACEHOLDER_DELAY_MILLIS = 200L
        private const val SECONDS_PER_DAY = 86_400L
    }
}

interface SignInMonitoringDataSource {
    suspend fun load(
        rangeStartEpochSeconds: Long,
        rangeEndEpochSeconds: Long,
    ): SignInMonitoringSnapshot
}

class StorageSignInMonitoringDataSource(
    private val signInAuditRepository: SignInAuditRepository,
    private val beaconScanAuditRepository: BeaconScanAuditRepository,
) : SignInMonitoringDataSource {
    override suspend fun load(
        rangeStartEpochSeconds: Long,
        rangeEndEpochSeconds: Long,
    ): SignInMonitoringSnapshot =
        SignInMonitoringSnapshot(
            signInAudits = signInAuditRepository.recent(50).map(SignInAuditEntity::toDisplay),
            beaconScanAudits = beaconScanAuditRepository.recent(50).map(BeaconScanAuditEntity::toDisplay),
            errorAggregates =
                signInAuditRepository.countByErrorWithin(
                    rangeStart = rangeStartEpochSeconds,
                    rangeEnd = rangeEndEpochSeconds,
                ).map(SignInErrorCount::toDisplay),
        )
}

data class SignInMonitoringSnapshot(
    val signInAudits: List<SignInAuditDisplay>,
    val beaconScanAudits: List<BeaconScanAuditDisplay>,
    val errorAggregates: List<SignInErrorAggregateDisplay>,
)

data class SignInMonitoringUiState(
    val isLoading: Boolean = false,
    val showPlaceholder: Boolean = false,
    val signInAudits: List<SignInAuditDisplay> = emptyList(),
    val beaconScanAudits: List<BeaconScanAuditDisplay> = emptyList(),
    val errorAggregates: List<SignInErrorAggregateDisplay> = emptyList(),
    val emptyMessage: String = "",
)

data class SignInAuditDisplay(
    val studentId: String,
    val bookingId: String,
    val result: String,
    val triggerSource: String,
)

data class BeaconScanAuditDisplay(
    val bookingId: String,
    val expectedMinors: String,
    val seenMinors: String,
    val matchedMinor: String,
    val durationMillis: Long,
)

data class SignInErrorAggregateDisplay(
    val error: String,
    val count: Long,
)

class SignInMonitoringViewModelFactory(
    private val source: SignInMonitoringDataSource,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SignInMonitoringViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return SignInMonitoringViewModel(source = source) as T
    }
}

private fun SignInAuditEntity.toDisplay(): SignInAuditDisplay =
    SignInAuditDisplay(
        studentId = studentId,
        bookingId = bookingId,
        result = signInError ?: rawMessage.ifBlank { "成功" },
        triggerSource = triggerSource,
    )

private fun BeaconScanAuditEntity.toDisplay(): BeaconScanAuditDisplay =
    BeaconScanAuditDisplay(
        bookingId = bookingId.orEmpty(),
        expectedMinors = expectedMinorsCsv.ifBlank { "-" },
        seenMinors = seenMinorsCsv.ifBlank { "-" },
        matchedMinor = matchedMinor?.toString() ?: "-",
        durationMillis = scanDurationMillis,
    )

private fun SignInErrorCount.toDisplay(): SignInErrorAggregateDisplay =
    SignInErrorAggregateDisplay(
        error = signInError ?: "成功/未分类",
        count = count,
    )

private fun SignInAuditDisplay.masked(): SignInAuditDisplay =
    copy(
        studentId = studentId.maskSensitive(),
        bookingId = bookingId.maskSensitive(),
    )
