package com.wuyi.libraryauto.core.network.seat

data class SearchPageContext(
    val searchApiUrl: String,
    val rawPayload: String,
    val defaultBeginTime: Int,
    val defaultDurationHours: Int,
    val defaultPeopleCount: Int,
    val categoryId: String,
    val contentId: String,
)
