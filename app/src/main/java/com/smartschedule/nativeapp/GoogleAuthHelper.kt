package com.smartschedule.nativeapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

fun Context.findActivity(): Activity? {
    var cur = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return null
}

class GoogleAuthHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences("SmartScheduleWidgetPrefs", Context.MODE_PRIVATE)
    private val credentialManager = CredentialManager.create(context)
    private val authClient = Identity.getAuthorizationClient(context)

    private val requestedScopes = listOf(
        Scope("https://www.googleapis.com/auth/calendar"),
        Scope("https://www.googleapis.com/auth/tasks")
    )

    var isGoogleConnectedPref: Boolean
        get() = prefs.getBoolean("google_connected", false)
        set(value) = prefs.edit().putBoolean("google_connected", value).apply()

    var savedAccountEmail: String
        get() = prefs.getString("google_account_email", "") ?: ""
        set(value) = prefs.edit().putString("google_account_email", value).apply()

    // หากมีการระบุ Web Client ID เพิ่มเติม สามารถยืนยันตัวตนผ่าน Credential Manager ก่อนขอสิทธิ์ Scope ได้
    suspend fun signInWithCredentialManagerIfConfigured(activity: Activity, webClientId: String = ""): String? {
        if (webClientId.isBlank()) return null
        return runCatching {
            val googleIdOption = GetSignInWithGoogleOption.Builder(webClientId.trim()).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activity, request)
            val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
            cred.id
        }.getOrNull()
    }

    // ขอ OAuth 2.0 Access Token สำหรับ Calendar & Tasks ผ่าน Android Client ID (Package + SHA-1)
    fun authorizeCalendarAndTasks(
        allowInteractiveResolution: Boolean,
        onTokenReady: (String) -> Unit,
        onNeedConsentScreen: (IntentSenderRequest) -> Unit,
        onError: (String) -> Unit
    ) {
        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

        authClient.authorize(authRequest)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (allowInteractiveResolution && pendingIntent != null) {
                        val senderReq = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        onNeedConsentScreen(senderReq)
                    } else if (!allowInteractiveResolution) {
                        onError("เซสชันหมดอายุ กรุณากดเชื่อมต่อใหม่อีกครั้ง")
                    } else {
                        onError("ไม่สามารถเปิดหน้าต่างขออนุญาตจาก Google ได้")
                    }
                } else {
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        isGoogleConnectedPref = true
                        onTokenReady(token)
                    } else {
                        onError("ไม่ได้รับ Access Token จาก Google")
                    }
                }
            }
            .addOnFailureListener { err ->
                onError(err.localizedMessage ?: "การขอสิทธิ์ Google ล้มเหลว")
            }
    }

    // รับผลลัพธ์หลังจากผู้ใช้กดอนุญาตในหน้าต่าง Google Consent
    fun handleAuthorizationResult(
        data: Intent?,
        onTokenReady: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            authClient.getAuthorizationResultFromIntent(data)
        }.onSuccess { result ->
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                isGoogleConnectedPref = true
                onTokenReady(token)
            } else {
                onError("ไม่ได้รับ Access Token หลังการอนุญาต")
            }
        }.onFailure { err ->
            onError(err.localizedMessage ?: "ยกเลิกการเข้าสู่ระบบหรือไม่สำเร็จ")
        }
    }

    suspend fun signOut() {
        isGoogleConnectedPref = false
        savedAccountEmail = ""
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
