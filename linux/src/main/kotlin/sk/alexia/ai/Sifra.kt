package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kľúč histórie na PC / Legion Go S. Hlavný kľúč (AES-256) je v súbore kluc.json v priečinku appky,
 * čitateľný len pre teba. Ak si nastavíš heslo, hlavný kľúč je navyše zašifrovaný heslom (PBKDF2, 310 000 kôl)
 * a bez hesla sa história nedá prečítať.
 */
object Sifra {
    private val rnd = SecureRandom()
    private var k: SecretKey? = null
    var zamknute by mutableStateOf(false)
        private set

    private fun subor() = File(Platforma.priecinok, "kluc.json")
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun zB64(s: String) = Base64.getDecoder().decode(s)

    fun maHeslo(): Boolean = runCatching { JSONObject(subor().readText()).has("sol") }.getOrDefault(false)

    /** volá sa pri štarte: bez hesla odomkne hneď, s heslom čaká na [odomkni] */
    fun priprav() {
        if (!subor().exists()) { k = SecretKeySpec(ByteArray(32).also { rnd.nextBytes(it) }, "AES"); zapis(null) }
        else if (maHeslo()) zamknute = true
        else k = SecretKeySpec(zB64(JSONObject(subor().readText()).getString("kluc")), "AES")
    }

    fun kluc(): SecretKey = k ?: throw IllegalStateException("História je zamknutá")

    private fun odvod(heslo: String, sol: ByteArray): SecretKey {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(heslo.toCharArray(), sol, 310_000, 256)).encoded, "AES")
    }

    fun odomkni(heslo: String): Boolean = runCatching {
        val j = JSONObject(subor().readText())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, odvod(heslo, zB64(j.getString("sol"))), GCMParameterSpec(128, zB64(j.getString("iv"))))
        k = SecretKeySpec(c.doFinal(zB64(j.getString("kluc"))), "AES")
        zamknute = false
        true
    }.getOrDefault(false)

    /** nastaví, zmení (null = bez hesla) heslo – hlavný kľúč ostáva, história sa nemusí prešifrovať */
    fun nastavHeslo(nove: String?) { kluc(); zapis(nove?.takeIf { it.isNotEmpty() }) }

    private fun zapis(heslo: String?) {
        val raw = k!!.encoded
        val j = JSONObject()
        if (heslo == null) j.put("kluc", b64(raw))
        else {
            val sol = ByteArray(16).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, odvod(heslo, sol))
            j.put("sol", b64(sol)).put("iv", b64(c.iv)).put("kluc", b64(c.doFinal(raw)))
        }
        val f = subor()
        f.writeText(j.toString())
        // len pre vlastníka (Linux); na Windows je %LOCALAPPDATA% súkromný priečinok používateľa
        runCatching { Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    }
}
