package com.wuyi.libraryauto.ui.repository.seat

internal fun buildEntryUrlCandidates(
    preferredEntryUrl: String,
    fallbackEntryUrls: List<String>,
): List<String> =
    buildList {
        preferredEntryUrl.trim()
            .takeIf(String::isNotBlank)
            ?.let(::add)
        fallbackEntryUrls
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { entryUrl -> contains(entryUrl) }
            .forEach(::add)
    }
