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

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.combo.core.model.AuthorizationRequest
import com.combo.core.utils.startPluginActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.random.Random

/**
 * 一个默认的、基于UI的统一授权处理器实现。
 * 它会启动一个通用的 AuthorizationActivity 来征求用户的意见。
 */
class DefaultAuthorizationHandler(private val context: Application) : IAuthorizationHandler {
    private val pendingRequests = ConcurrentHashMap<Int, Continuation<Boolean>>()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AuthorizationActivity.ACTION_AUTHORIZATION_RESULT) {
                    val requestCode = intent.getIntExtra(AuthorizationActivity.EXTRA_REQUEST_CODE, -1)
                    val granted = intent.getBooleanExtra(AuthorizationActivity.EXTRA_RESULT_GRANTED, false)
                    pendingRequests.remove(requestCode)?.resumeWith(Result.success(granted))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AuthorizationActivity.ACTION_AUTHORIZATION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override suspend fun onAuthorizationRequest(request: AuthorizationRequest): Boolean = suspendCancellableCoroutine { continuation ->
        val requestCode = Random.nextInt()
        pendingRequests[requestCode] = continuation

        continuation.invokeOnCancellation {
            pendingRequests.remove(requestCode)
        }

        context.startPluginActivity(AuthorizationActivity::class.java) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AuthorizationActivity.EXTRA_REQUEST_CODE, requestCode)
            putExtra(AuthorizationActivity.EXTRA_AUTH_REQUEST, request)
        }
    }
}