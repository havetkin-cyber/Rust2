package sk.alexia.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Kľúč histórie v Android Keystore – nedá sa z telefónu vytiahnuť, ani keď niekto skopíruje súbory appky. */
object Sifra {
    private const val ALIAS = "alexia_historia"
    private var k: SecretKey? = null

    fun kluc(): SecretKey {
        k?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existujuci = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val novy = existujuci ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        k = novy
        return novy
    }
}
