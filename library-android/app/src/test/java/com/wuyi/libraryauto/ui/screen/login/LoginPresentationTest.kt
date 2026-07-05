package com.wuyi.libraryauto.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.LoginUiState
import org.junit.Test

class LoginPresentationTest {
    @Test
    fun `login presentation exposes idle copy and enabled submit action`() {
        val presentation = buildLoginPresentation(LoginUiState())

        assertThat(presentation.title).isEqualTo("登录")
        assertThat(presentation.statusLabel).isEqualTo("待登录")
        assertThat(presentation.statusTone).isEqualTo(StatusTone.Neutral)
        assertThat(presentation.primaryButtonLabel).isEqualTo("登录并进入任务")
        assertThat(presentation.primaryButtonEnabled).isTrue()
        assertThat(presentation.passwordSupportText).isEqualTo("可留空，默认使用学号")
    }

    @Test
    fun `login presentation marks loading state as busy`() {
        val presentation = buildLoginPresentation(LoginUiState(isLoading = true))

        assertThat(presentation.statusLabel).isEqualTo("正在验证")
        assertThat(presentation.statusTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.primaryButtonLabel).isEqualTo("登录中...")
        assertThat(presentation.primaryButtonEnabled).isFalse()
    }

    @Test
    fun `login presentation exposes error and logged in states`() {
        val error = buildLoginPresentation(LoginUiState(errorMessage = "认证失败"))
        val loggedIn = buildLoginPresentation(LoginUiState(loggedIn = true))

        assertThat(error.statusLabel).isEqualTo("需要处理")
        assertThat(error.statusTone).isEqualTo(StatusTone.Negative)
        assertThat(error.errorMessage).isEqualTo("认证失败")
        assertThat(error.primaryButtonEnabled).isTrue()
        assertThat(loggedIn.statusLabel).isEqualTo("已登录")
        assertThat(loggedIn.statusTone).isEqualTo(StatusTone.Positive)
        assertThat(loggedIn.primaryButtonEnabled).isFalse()
    }
}
