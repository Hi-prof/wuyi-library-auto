package com.wuyi.libraryauto.core.network.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchUrlResolverTest {

    @Test
    fun `extractSearchApiUrl resolves fragment route into api url`() {
        val entryUrl =
            "https://wuyiu.huitu.zhishulib.com/#!/Seat/Index/searchSeats?LAB_JSON=1&time=1710000000"

        val actual = checkNotNull(SearchUrlResolver.extractSearchApiUrl(entryUrl))

        assertThat(actual)
            .isEqualTo("https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?time=1710000000")
    }

    @Test
    fun `extractSearchApiUrl strips lab json from direct search api url`() {
        val entryUrl =
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?LAB_JSON=1&room=201"

        val actual = checkNotNull(SearchUrlResolver.extractSearchApiUrl(entryUrl))

        assertThat(actual)
            .isEqualTo("https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?room=201")
    }

    @Test
    fun `extractSearchApiUrl keeps encoded query intact`() {
        val entryUrl =
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?keyword=a%2Bb&LAB_JSON=1"

        val actual = checkNotNull(SearchUrlResolver.extractSearchApiUrl(entryUrl))

        assertThat(actual)
            .isEqualTo("https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?keyword=a%2Bb")
    }

    @Test
    fun `extractSearchApiUrl returns null for non target path`() {
        val extraPathUrl =
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeatsExtra?room=201"
        val childPathUrl =
            "https://wuyiu.huitu.zhishulib.com/proxy/Seat/Index/searchSeats/child?room=201"

        assertThat(SearchUrlResolver.extractSearchApiUrl(extraPathUrl)).isNull()
        assertThat(SearchUrlResolver.extractSearchApiUrl(childPathUrl)).isNull()
    }

    @Test
    fun `extractEntryApiUrl prefers fragment route over shell page`() {
        val entryUrl =
            "https://wuyiu.huitu.zhishulib.com/app-shell#!/Space/Index/detail?content=201"

        val actual = SearchUrlResolver.extractEntryApiUrl(entryUrl)

        assertThat(actual)
            .isEqualTo("https://wuyiu.huitu.zhishulib.com/Space/Index/detail?content=201")
    }

    @Test
    fun `extractSearchApiUrlFromPayload walks nested link payload recursively`() {
        val payload =
            """
            {
              "content": {
                "defaultItems": [
                  {
                    "children": [
                      {
                        "link": {
                          "url": "/Seat/Index/searchSeats?LAB_JSON=1&content_id=201"
                        }
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()

        val actual =
            SearchUrlResolver.extractSearchApiUrlFromPayload(
                payloadJson = payload,
                requestUrl = "https://wuyiu.huitu.zhishulib.com/Space/Index/detail?content=201",
            )

        assertThat(actual)
            .isEqualTo(
                "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?content_id=201"
            )
    }

    @Test
    fun `extractSearchApiUrlsFromPayload preserves multiple candidates in order`() {
        val payload =
            """
            {
              "content": {
                "defaultItems": [
                  {
                    "link": {
                      "url": "/Seat/Index/searchSeats?LAB_JSON=1&content_id=999"
                    }
                  },
                  {
                    "children": [
                      {
                        "link": {
                          "url": "/Seat/Index/searchSeats?LAB_JSON=1&content_id=301"
                        }
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()

        val actual =
            SearchUrlResolver.extractSearchApiUrlsFromPayload(
                payloadJson = payload,
                requestUrl = "https://wuyiu.huitu.zhishulib.com/Space/Index/detail?content=201",
            )

        assertThat(actual).containsExactly(
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?content_id=999",
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?content_id=301",
        ).inOrder()
    }
}
