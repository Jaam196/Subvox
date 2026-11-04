package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Abstracción inyectable para obtener la clave secreta AES-256 utilizada para el cifrado de la API key.
 *
 * Separa de manera estricta la implementación de producción (AndroidKeystoreKeyProvider)
 * de la implementación para pruebas locales JVM/Robolectric (InsecureTestKeyProvider),
 * impidiendo que en producción se apliquen trucos de texto plano o fallback inseguro si el Keystore falla.
 */
interface KeyProvider {
    /**
     * Obtiene o genera la clave secreta protegida.
     * Devuelve null si el dispositivo no soporta o falla en inicializar el Keystore de hardware.
     */
    fun getOrCreateSecretKey(): SecretKey?

    /**
     * Indica si el proveedor cuenta con protección de hardware / Keystore del sistema operativo.
     */
    fun isSecureHardware(): Boolean
}

/**
 * Implementación de producción basada en el AndroidKeyStore del sistema operativo.
 *
 * Utiliza hardware seguro (TEE / StrongBox) cuando está disponible en el dispositivo móvil.
 * Si falla, devuelve null para que el almacén no guarde nada en lugar de recurrir a texto plano.
 */
class AndroidKeystoreKeyProvider(
    private val keyAlias: String = DEFAULT_KEYSTORE_ALIAS
) : KeyProvider {

    companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_KEYSTORE_ALIAS = "anime_sub_reader_gemini_user_key_v1"
    }

    override fun getOrCreateSecretKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
                load(null)
            }
            val existingEntry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (existingEntry != null) {
                existingEntry.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        }.getOrNull()
    }

    override fun isSecureHardware(): Boolean = true
}

/**
 * Proveedor inseguro utilizado EXCLUSIVAMENTE en entornos de pruebas automatizadas
 * (JVM puro o Robolectric) donde el keystore de hardware de Android no está emulado.
 *
 * NUNCA se utiliza de forma implícita en producción.
 */
class InsecureTestKeyProvider(
    private var simulatedFailure: Boolean = false
) : KeyProvider {

    private var inMemoryKey: SecretKey? = null

    init {
        generateNewKey()
    }

    fun setSimulateFailure(fail: Boolean) {
        simulatedFailure = fail
    }

    fun invalidateKey() {
        // Simula la invalidación de clave del keystore cambiando los bytes de la clave en memoria
        generateNewKey()
    }

    private fun generateNewKey() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        inMemoryKey = keyGen.generateKey()
    }

    override fun getOrCreateSecretKey(): SecretKey? {
        if (simulatedFailure) return null
        return inMemoryKey
    }

    override fun isSecureHardware(): Boolean = false
}
