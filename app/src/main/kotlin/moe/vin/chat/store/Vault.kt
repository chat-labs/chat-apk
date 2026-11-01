package moe.vin.chat.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject

// Everything kept on the phone, sealed. A random key seals the files, and that key is sealed in
// turn by what the user chose: a passphrase (Argon2id), this phone's Keystore, or both, the
// passphrase's seal inside the Keystore's, so a copy of the files can't be opened anywhere else.
class Vault(private val dir: File) {
    enum class Mode(val key: String) {
        BOTH("passphrase+device"),
        DEVICE("device"),
        PASSPHRASE("passphrase");

        val needsPassphrase get() = this != DEVICE
        val usesDevice get() = this != PASSPHRASE
    }

    class WrongPassphrase : Exception()

    private val random = SecureRandom()
    private var key: ByteArray? = null
    private val header get() = File(dir, "header.json")

    val exists get() = header.isFile
    val unlocked get() = key != null
    val mode: Mode? get() = if (!exists) null else Mode.entries.firstOrNull { it.key == JSONObject(header.readText()).optString("mode") }

    fun create(mode: Mode, passphrase: CharArray?) {
        dir.mkdirs()
        val k = ByteArray(32).also(random::nextBytes)
        writeHeader(mode, k, passphrase)
        key = k
    }

    // Seals the same key again another way, as the settings page changes it.
    fun changeMode(mode: Mode, passphrase: CharArray?) {
        val k = key ?: error("locked")
        writeHeader(mode, k, passphrase)
    }

    fun unlock(passphrase: CharArray?) {
        val h = JSONObject(header.readText())
        val mode = Mode.entries.first { it.key == h.getString("mode") }
        var sealed = b64(h.getString("sealed"))
        if (mode.usesDevice) sealed = deviceOpen(sealed)
        if (mode.needsPassphrase) {
            val kek = argon2(passphrase ?: CharArray(0), b64(h.getString("salt")), h.getJSONObject("argon2"))
            try {
                sealed = open(kek, sealed, AAD_KEY)
            } catch (e: Exception) {
                throw WrongPassphrase()
            } finally {
                kek.fill(0)
            }
        }
        key = sealed
    }

    fun lock() {
        key?.fill(0)
        key = null
    }

    // Deletes everything, and the Keystore key with it.
    fun erase() {
        lock()
        dir.deleteRecursively()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
    }

    fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return open(key ?: error("locked"), f.readBytes(), name.toByteArray())
    }

    fun write(name: String, data: ByteArray) {
        val k = key ?: error("locked")
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".new")
        FileOutputStream(tmp).use { out ->
            out.write(seal(k, data, name.toByteArray()))
            out.fd.sync()
        }
        tmp.renameTo(f)
    }

    // A file of records, each sealed on its own, so adding one doesn't rewrite the rest.
    fun append(name: String, record: ByteArray) {
        val k = key ?: error("locked")
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val sealed = seal(k, record, name.toByteArray())
        FileOutputStream(f, true).use { out ->
            DataOutputStream(out).apply { writeInt(sealed.size); write(sealed); flush() }
        }
    }

    fun records(name: String): List<ByteArray> {
        val f = File(dir, name)
        if (!f.isFile) return emptyList()
        val k = key ?: error("locked")
        val out = ArrayList<ByteArray>()
        DataInputStream(f.inputStream().buffered()).use { input ->
            while (input.available() >= 4) {
                val n = input.readInt()
                if (n <= 0 || n > MAX_RECORD) break
                val sealed = ByteArray(n)
                input.readFully(sealed)
                try { out += open(k, sealed, name.toByteArray()) } catch (e: Exception) { break }
            }
        }
        return out
    }

    fun rewrite(name: String, records: List<ByteArray>) {
        val k = key ?: error("locked")
        val f = File(dir, name)
        val tmp = File(f.path + ".new")
        FileOutputStream(tmp).use { out ->
            val d = DataOutputStream(out)
            for (r in records) {
                val sealed = seal(k, r, name.toByteArray())
                d.writeInt(sealed.size)
                d.write(sealed)
            }
            d.flush()
            out.fd.sync()
        }
        tmp.renameTo(f)
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }

    private fun writeHeader(mode: Mode, k: ByteArray, passphrase: CharArray?) {
        val h = JSONObject().put("v", 1).put("mode", mode.key)
        var sealed = k.copyOf()
        if (mode.needsPassphrase) {
            val salt = ByteArray(16).also(random::nextBytes)
            val params = JSONObject().put("m", ARGON_MEMORY_KB).put("t", ARGON_PASSES).put("p", 1)
            val kek = argon2(passphrase ?: CharArray(0), salt, params)
            sealed = seal(kek, sealed, AAD_KEY)
            kek.fill(0)
            h.put("salt", b64(salt)).put("argon2", params)
        }
        if (mode.usesDevice) sealed = deviceSeal(sealed)
        h.put("sealed", b64(sealed))
        val tmp = File(dir, "header.json.new")
        tmp.writeText(h.toString())
        tmp.renameTo(header)
    }

    private fun argon2(passphrase: CharArray, salt: ByteArray, p: JSONObject): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(p.getInt("m"))
            .withIterations(p.getInt("t"))
            .withParallelism(p.getInt("p"))
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return out
    }

    private fun seal(k: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(data)
    }

    private fun open(k: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    // The Keystore key: it never leaves the phone's secure hardware, and only works while the phone
    // is unlocked.
    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun make(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
        return try { make(true) } catch (e: StrongBoxUnavailableException) { make(false) }
    }

    private fun deviceSeal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.ENCRYPT_MODE, deviceKey())
        c.updateAAD(AAD_DEVICE)
        return c.iv + c.doFinal(data)
    }

    private fun deviceOpen(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(AAD_DEVICE)
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "chat-vault-1"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val ARGON_MEMORY_KB = 64 * 1024
        const val ARGON_PASSES = 3
        const val MAX_RECORD = 1 shl 20
        val AAD_KEY = "chat vault key".toByteArray()
        val AAD_DEVICE = "chat vault device".toByteArray()

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }
}
