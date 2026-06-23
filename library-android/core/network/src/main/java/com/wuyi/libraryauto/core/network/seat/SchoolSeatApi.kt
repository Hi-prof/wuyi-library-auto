package com.wuyi.libraryauto.core.network.seat

import java.net.URI

interface SchoolSeatApi {

    fun fetchSearchPage(url: String): String

    fun searchSeats(url: String, requestBody: String): String

    fun reserveSeats(url: String, requestBody: String): String

    fun fetchBookingList(url: String): String

    fun checkIn(url: String): String

    fun checkout(url: String): String

    fun cancelBooking(url: String): String

    companion object {
        const val SEARCH_SEATS_PATH = "/Seat/Index/searchSeats"
        const val BOOK_SEATS_PATH = "/Seat/Index/bookSeats"
        const val MY_BOOKING_LIST_PATH = "/Seat/Index/myBookingList"
        const val CHECKIN_PATH = "/Seat/Index/checkIn"
        const val CHECKOUT_PATH = "/Seat/Index/checkOut"
        const val CANCEL_BOOKING_PATH = "/Seat/Index/cancelBooking"
        const val LAB_JSON_QUERY = "LAB_JSON=1"

        fun appendLabJson(url: String): String {
            val parsed = URI(url)
            val querySegments =
                parsed.rawQuery
                    ?.split("&")
                    ?.filter(String::isNotBlank)
                    ?.filterNot { segment -> segment.substringBefore("=") == "LAB_JSON" }
                    .orEmpty() + LAB_JSON_QUERY
            return buildString {
                append(parsed.scheme)
                append("://")
                append(parsed.rawAuthority)
                append(parsed.rawPath.orEmpty())
                append("?")
                append(querySegments.joinToString("&"))
            }
        }
    }
}
