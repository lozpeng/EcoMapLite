/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.combo.core.security.auth

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.combo.core.component.activity.BasePluginActivity
import com.combo.core.model.AuthorizationRequest
import com.combo.core.ui.AuthorizationScreen
import com.combo.core.ui.component.SystemAppearance

/**
 * 通用的授权UI界面，用于处理API权限请求和插件安装请求。
 * 新版本已重构，UI逻辑完全委托给 AuthorizationScreen Composable。
 */
class AuthorizationActivity : BasePluginActivity() {

    companion object {
        const val EXTRA_REQUEST_CODE = "request_code"
        const val EXTRA_AUTH_REQUEST = "auth_request"
        const val ACTION_AUTHORIZATION_RESULT = "com.combo.core.security.AUTHORIZATION_RESULT"
        const val EXTRA_RESULT_GRANTED = "result_granted"
    }

    private var requestCode: Int = -1
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = proxyActivity?.intent

        if (intent == null) {
            finishWithResult(-1, false)
            return
        }

        this.requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)

        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_AUTH_REQUEST, AuthorizationRequest::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_AUTH_REQUEST) as? AuthorizationRequest
        }

        if (request == null) {
            finishWithResult(this.requestCode, false)
            return
        }

        proxyActivity?.setContent {
            SystemAppearance(!isSystemInDarkTheme())
            AuthorizationScreen(
                request = request,
                onResult = { granted ->
                    finishWithResult(this.requestCode, granted)
                },
                onExit = {
                    finishWithResult(this.requestCode, false)
                }
            )
        }
    }

    private fun finishWithResult(requestCode: Int, granted: Boolean) {
        if (resultSent) return
        resultSent = true

        val resultIntent = Intent(ACTION_AUTHORIZATION_RESULT).apply {
            setPackage(proxyActivity?.packageName)
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            putExtra(EXTRA_RESULT_GRANTED, granted)
        }
        proxyActivity?.sendBroadcast(resultIntent)
        proxyActivity?.finish()
    }

    override fun onPause() {
        super.onPause()
        if (proxyActivity?.isFinishing == true && !resultSent) {
            finishWithResult(this.requestCode, false)
        }
    }
}