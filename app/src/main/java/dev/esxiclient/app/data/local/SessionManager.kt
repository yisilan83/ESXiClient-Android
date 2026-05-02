package dev.esxiclient.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "esxi_settings")

class SessionManager(private val context: Context) {

    companion object {
        val HOST_KEY          = stringPreferencesKey("host")
        val SESSION_ID_KEY    = stringPreferencesKey("session_id")
        val USERNAME_KEY      = stringPreferencesKey("username")
        val PASSWORD_KEY      = stringPreferencesKey("password")
        val REMEMBER_ME_KEY   = booleanPreferencesKey("remember_me")
        val CHECK_HTTP_KEY    = booleanPreferencesKey("check_http")

        // ── Legacy keys that may exist from older app versions ──────
        // are ignored, just kept here for documentation
    }

    suspend fun saveSession(
        host: String,
        sessionId: String,
        username: String,
        password: String,
        rememberMe: Boolean,
        checkHttp: Boolean = false
    ) {
        context.dataStore.edit { prefs ->
            prefs[HOST_KEY]        = host
            prefs[SESSION_ID_KEY]  = sessionId
            prefs[USERNAME_KEY]    = username
            prefs[REMEMBER_ME_KEY] = rememberMe
            prefs[CHECK_HTTP_KEY]  = checkHttp
            if (rememberMe) {
                prefs[PASSWORD_KEY] = password
            } else {
                prefs.remove(PASSWORD_KEY)
            }
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(SESSION_ID_KEY)
        }
    }

    val hostFlow:        Flow<String?> = context.dataStore.data.map { it[HOST_KEY] }
    val sessionIdFlow:   Flow<String?> = context.dataStore.data.map { it[SESSION_ID_KEY] }
    val usernameFlow:    Flow<String?> = context.dataStore.data.map { it[USERNAME_KEY] }
    val passwordFlow:    Flow<String?> = context.dataStore.data.map { it[PASSWORD_KEY] }
    val rememberMeFlow:  Flow<Boolean> = context.dataStore.data.map { it[REMEMBER_ME_KEY] ?: false }
    val checkHttpFlow:   Flow<Boolean> = context.dataStore.data.map { it[CHECK_HTTP_KEY]   ?: false }
}
