package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Estados posibles de la clave de usuario en el almacén de seguridad.
 */
sealed class UserApiKeyState {
    /** No hay ninguna clave configurada en el dispositivo. */
    data object NoKey : UserApiKeyState()

    /** Clave configurada y descifrada exitosamente (expone la versión enmascarada para UI). */
    data class Valid(val maskedKey: String) : UserApiKeyState()

    /**
     * El dispositivo contenía datos cifrados pero el descifrado falló
     * (por ejemplo, porque la clave del Keystore fue invalidada tras cambio de biometría o corrupción).
     */
    data object Unreadable : UserApiKeyState()
}

/**
 * Resultado de la operación [ApiKeyStore.saveKey].
 */
sealed class SaveKeyResult {
    data object Success : SaveKeyResult()
    data object EmptyKey : SaveKeyResult()
    data class HardwareUnavailable(val message: String) : SaveKeyResult()
    data class EncryptionError(val message: String) : SaveKeyResult()
}

/**
 * Resultado de validación al pulsar "Probar clave" contra la API de Gemini TTS.
 */
sealed class ApiKeyValidationResult {
    data object Idle : ApiKeyValidationResult()
    data object Testing : ApiKeyValidationResult()
    data class Valid(val message: String) : ApiKeyValidationResult()
    data class InvalidKey(val message: String) : ApiKeyValidationResult()
    data class QuotaExceeded(val message: String) : ApiKeyValidationResult()
    data class NoConnection(val message: String) : ApiKeyValidationResult()
    data class OtherError(val message: String) : ApiKeyValidationResult()
}

/**
 * Almacén seguro de la API Key del usuario en el dispositivo.
 *
 * - Cifra y descifra con AES-256-GCM usando exclusivamente la clave provista por [KeyProvider].
 * - En producción, [AndroidKeystoreKeyProvider] delega a AndroidKeyStore con hardware TEE/StrongBox.
 *   Si el hardware Keystore no está disponible o falla, [saveKey] devuelve [SaveKeyResult.HardwareUnavailable]
 *   y NO guarda nada en texto plano ni en preferencias.
 * - Detecta tres estados en [getKeyState]: NoKey, Valid, Unreadable.
 *   Si la clave es ilegible, borra el ciphertext corrupto automáticamente y expone [UserApiKeyState.Unreadable].
 * - Borra de manera preventiva cualquier resto de claves de fallback inseguras creadas en versiones previas.
 * - El archivo `anime_sub_secure_api_key_prefs.xml` está excluido de `backup_rules.xml` y `data_extraction_rules.xml`.
 * - La clave NUNCA se registra en Logcat ni en mensajes de excepción.
 */
class ApiKeyStore(
    context: Context,
    private val keyProvider: KeyProvider = AndroidKeystoreKeyProvider()
) {

    companion object {
        const val PREFS_FILE_NAME = "anime_sub_secure_api_key_prefs"
        private const val PREF_KEY_CIPHERTEXT = "encrypted_gemini_api_key_blob"
        private const val PREF_KEY_IV = "encrypted_gemini_api_key_iv"
        private const val LEGACY_FALLBACK_ALIAS = "jvm_fallback_key_material"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        // Flows reactivos compartidos entre instancias
        private val _hasKeyFlow = MutableStateFlow(false)
        private val _maskedKeyFlow = MutableStateFlow("")
        private val _keyStateFlow = MutableStateFlow<UserApiKeyState>(UserApiKeyState.NoKey)
        private val _keyVersionFlow = MutableStateFlow(0L)

        /**
         * Enmascara una clave mostrando únicamente los últimos 4 caracteres (ej. "••••••••••••ABCD").
         * Nunca devuelve la clave completa.
         */
        fun maskApiKey(rawKey: String): String {
            val trimmed = rawKey.trim()
            if (trimmed.isEmpty()) return ""
            val visibleTail = if (trimmed.length <= 4) {
                trimmed.takeLast(1)
            } else {
                trimmed.takeLast(4)
            }
            return "••••••••••••$visibleTail"
        }

        /**
         * Elimina cualquier aparición accidental de una clave o parámetro `key=` en mensajes de error o URLs.
         */
        fun sanitizeErrorMessage(rawMessage: String?, currentKey: String = ""): String {
            if (rawMessage.isNullOrBlank()) return "Error de red o servicio"
            var sanitized = rawMessage
                .replace(Regex("""([?&]key=)[^&\s"']+""", RegexOption.IGNORE_CASE), "$1***")
                .replace(Regex("""AIza[0-9A-Za-z\-_]{10,}"""), "AIza***")
            val trimmedKey = currentKey.trim()
            if (trimmedKey.length >= 4) {
                sanitized = sanitized.replace(trimmedKey, "***")
            }
            return sanitized.take(120)
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    val hasKeyFlow: StateFlow<Boolean> = _hasKeyFlow.asStateFlow()
    val maskedKeyFlow: StateFlow<String> = _maskedKeyFlow.asStateFlow()
    val keyStateFlow: StateFlow<UserApiKeyState> = _keyStateFlow.asStateFlow()
    val keyVersionFlow: StateFlow<Long> = _keyVersionFlow.asStateFlow()

    init {
        purgeInsecureFallbackKeyMaterial()
        refreshReactiveState()
    }

    /**
     * Borra de forma inmediata cualquier resto antiguo de fallback de clave en preferencias
     * si alguna versión anterior lo escribió.
     */
    private fun purgeInsecureFallbackKeyMaterial() {
        if (prefs.contains(LEGACY_FALLBACK_ALIAS)) {
            prefs.edit().remove(LEGACY_FALLBACK_ALIAS).apply()
        }
    }

    /**
     * Consulta el estado actual tipado de la clave (NoKey, Valid o Unreadable).
     */
    @Synchronized
    fun getKeyState(): UserApiKeyState {
        val cipherBase64 = prefs.getString(PREF_KEY_CIPHERTEXT, null)
        val ivBase64 = prefs.getString(PREF_KEY_IV, null)
        if (cipherBase64.isNullOrBlank() || ivBase64.isNullOrBlank()) {
            return UserApiKeyState.NoKey
        }

        val secretKey = keyProvider.getOrCreateSecretKey()
        if (secretKey == null) {
            // No hay hardware seguro para descifrar el blob existente
            clearCorruptCiphertext()
            return UserApiKeyState.Unreadable
        }

        return try {
            val cipherBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)
            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decrypted = cipher.doFinal(cipherBytes)
            val plain = String(decrypted, StandardCharsets.UTF_8).trim()
            if (plain.isNotEmpty()) {
                UserApiKeyState.Valid(maskApiKey(plain))
            } else {
                UserApiKeyState.NoKey
            }
        } catch (_: Throwable) {
            // Fallo en descifrado (clave Keystore invalidada, blob truncado, etc.)
            clearCorruptCiphertext()
            UserApiKeyState.Unreadable
        }
    }

    /**
     * Devuelve la clave en texto plano para síntesis interna.
     * Si no hay clave o es ilegible, devuelve cadena vacía `""`.
     * Nunca imprime en Logcat el contenido de la clave ni detalles sensibles.
     */
    @Synchronized
    fun getKey(): String {
        val cipherBase64 = prefs.getString(PREF_KEY_CIPHERTEXT, null)
        val ivBase64 = prefs.getString(PREF_KEY_IV, null)
        if (cipherBase64.isNullOrBlank() || ivBase64.isNullOrBlank()) {
            return ""
        }
        val secretKey = keyProvider.getOrCreateSecretKey() ?: run {
            clearCorruptCiphertext()
            return ""
        }

        return try {
            val cipherBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)
            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decrypted = cipher.doFinal(cipherBytes)
            String(decrypted, StandardCharsets.UTF_8).trim()
        } catch (_: Throwable) {
            clearCorruptCiphertext()
            ""
        }
    }

    /**
     * Guarda la clave de forma cifrada mediante [KeyProvider].
     * Si el proveedor falla o no está disponible, no guarda nada y devuelve [SaveKeyResult.HardwareUnavailable].
     */
    @Synchronized
    fun saveKey(rawKey: String): SaveKeyResult {
        val cleaned = rawKey.trim()
        if (cleaned.isEmpty()) {
            clearKey()
            return SaveKeyResult.EmptyKey
        }

        val secretKey = keyProvider.getOrCreateSecretKey()
            ?: return SaveKeyResult.HardwareUnavailable("Este dispositivo no permite guardar la clave de forma segura.")

        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ivBytes = cipher.iv
            val cipherBytes = cipher.doFinal(cleaned.toByteArray(StandardCharsets.UTF_8))

            val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(ivBytes, Base64.NO_WRAP)

            val committed = prefs.edit()
                .putString(PREF_KEY_CIPHERTEXT, cipherBase64)
                .putString(PREF_KEY_IV, ivBase64)
                .commit()

            if (committed) {
                refreshReactiveState()
                SaveKeyResult.Success
            } else {
                SaveKeyResult.EncryptionError("Error al escribir preferencias en disco.")
            }
        } catch (_: Throwable) {
            SaveKeyResult.EncryptionError("Error en cifrado AES-GCM.")
        }
    }

    /**
     * Borra de forma inmediata la clave cifrada almacenada en el dispositivo.
     */
    @Synchronized
    fun clearKey() {
        prefs.edit()
            .remove(PREF_KEY_CIPHERTEXT)
            .remove(PREF_KEY_IV)
            .remove(LEGACY_FALLBACK_ALIAS)
            .commit()
        refreshReactiveState()
    }

    private fun clearCorruptCiphertext() {
        prefs.edit()
            .remove(PREF_KEY_CIPHERTEXT)
            .remove(PREF_KEY_IV)
            .commit()
        refreshReactiveState()
    }

    @Synchronized
    fun hasKey(): Boolean {
        return getKey().isNotBlank()
    }

    @Synchronized
    fun getMaskedKey(): String {
        val current = getKey()
        return if (current.isBlank()) "" else maskApiKey(current)
    }

    fun refreshReactiveState() {
        val state = getKeyState()
        _keyStateFlow.value = state
        when (state) {
            is UserApiKeyState.Valid -> {
                _hasKeyFlow.value = true
                _maskedKeyFlow.value = state.maskedKey
            }
            UserApiKeyState.NoKey, UserApiKeyState.Unreadable -> {
                _hasKeyFlow.value = false
                _maskedKeyFlow.value = ""
            }
        }
        _keyVersionFlow.value = _keyVersionFlow.value + 1L
    }
}
