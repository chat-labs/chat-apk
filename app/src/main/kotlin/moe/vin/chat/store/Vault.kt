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

    enum class UnlockOutcome { NORMAL, SHADOW }

    class WrongPassphrase(val triesLeft: Int? = null) : Exception()
    class VaultDestroyed : Exception()

    private val random = SecureRandom()
    private val lockObj = Any()
    @Volatile private var key: ByteArray? = null
    private val header get() = File(dir, "header.json")

    val exists get() = header.isFile
    val unlocked get() = key != null
    val mode: Mode?
        get() = if (!exists) null else runCatching {
            val m = JSONObject(header.readText()).optString("mode")
            Mode.entries.firstOrNull { it.key == m }
        }.getOrNull()

    val hasShadow: Boolean
        get() = exists && runCatching { JSONObject(header.readText()).has("shadow") }.getOrDefault(false)

    val destroyLimit: Int
        get() = if (!exists) 0 else runCatching { JSONObject(header.readText()).optInt("destroyLimit", 0) }.getOrDefault(0)

    fun create(mode: Mode, passphrase: CharArray?) = synchronized(lockObj) {
        dir.mkdirs()
        val k = ByteArray(32).also(random::nextBytes)
        writeHeader(mode, k, passphrase, shadow = null, destroyLimit = 0)
        key = k
    }

    // Seals the same key again another way, as the settings page changes it.
    fun changeMode(mode: Mode, passphrase: CharArray?) = synchronized(lockObj) {
        val k = key ?: error("locked")
        val limit = destroyLimit
        writeHeader(mode, k, passphrase, shadow = null, destroyLimit = if (mode.needsPassphrase) limit else 0)
    }

    fun setDestroyLimit(limit: Int) = synchronized(lockObj) {
        if (!exists) return@synchronized
        val h = JSONObject(header.readText())
        h.put("destroyLimit", limit.coerceAtLeast(0))
        h.put("failedTries", 0)
        saveHeaderJson(h)
    }

    // Sets or clears a shadow passphrase: entering it on the unlock screen wipes the real vault
    // and promotes an empty decoy vault in its place.
    fun setShadow(passphrase: CharArray?) = synchronized(lockObj) {
        if (!exists) return@synchronized
        val h = JSONObject(header.readText())
        val m = Mode.entries.first { it.key == h.getString("mode") }
        if (passphrase == null || !m.needsPassphrase) {
            h.remove("shadow")
            saveHeaderJson(h)
            return@synchronized
        }
        val decoyKey = ByteArray(32).also(random::nextBytes)
        val salt = ByteArray(16).also(random::nextBytes)
        val params = JSONObject().put("m", ARGON_MEMORY_KB).put("t", ARGON_PASSES).put("p", 1)
        val kek = argon2(passphrase, salt, params)
        var sealed = seal(kek, decoyKey, AAD_KEY)
        kek.fill(0)
        decoyKey.fill(0)
        if (m.usesDevice) sealed = deviceSeal(sealed)
        val sh = JSONObject().put("salt", b64(salt)).put("argon2", params).put("sealed", b64(sealed))
        h.put("shadow", sh)
        saveHeaderJson(h)
    }

    fun unlock(passphrase: CharArray?): UnlockOutcome = synchronized(lockObj) {
        tryOpen(passphrase, keepExistingKey = false)
    }

    // Checks the passphrase (or device key) while the UI is locked with rooms still connected.
    fun checkUiUnlock(passphrase: CharArray?): UnlockOutcome = synchronized(lockObj) {
        tryOpen(passphrase, keepExistingKey = true)
    }

    private fun tryOpen(passphrase: CharArray?, keepExistingKey: Boolean): UnlockOutcome {
        val h = JSONObject(header.readText())
        val m = Mode.entries.first { it.key == h.getString("mode") }
        var mainSealed = b64(h.getString("sealed"))
        if (m.usesDevice) mainSealed = deviceOpen(mainSealed)
        if (!m.needsPassphrase) {
            if (!keepExistingKey) key = mainSealed else mainSealed.fill(0)
            return UnlockOutcome.NORMAL
        }
        val pw = passphrase ?: CharArray(0)
        val kek = argon2(pw, b64(h.getString("salt")), h.getJSONObject("argon2"))
        val openedMain = try {
            open(kek, mainSealed, AAD_KEY)
        } catch (e: Exception) {
            null
        } finally {
            kek.fill(0)
        }
        if (openedMain != null) {
            if (h.optInt("failedTries", 0) != 0) {
                h.put("failedTries", 0)
                saveHeaderJson(h)
            }
            if (!keepExistingKey) {
                key?.fill(0)
                key = openedMain
            } else {
                openedMain.fill(0)
            }
            return UnlockOutcome.NORMAL
        }

        // Check shadow passphrase if one is set.
        val shadow = h.optJSONObject("shadow")
        if (shadow != null) {
            val shadowKek = argon2(pw, b64(shadow.getString("salt")), shadow.getJSONObject("argon2"))
            val openedShadow = try {
                var shSealed = b64(shadow.getString("sealed"))
                if (m.usesDevice) shSealed = deviceOpen(shSealed)
                open(shadowKek, shSealed, AAD_KEY)
            } catch (e: Exception) {
                null
            } finally {
                shadowKek.fill(0)
            }
            if (openedShadow != null) {
                promoteShadow(h, shadow, openedShadow)
                return UnlockOutcome.SHADOW
            }
        }

        // Wrong passphrase: count against self-destruct if enabled.
        val limit = h.optInt("destroyLimit", 0)
        if (limit > 0) {
            val tries = h.optInt("failedTries", 0) + 1
            if (tries >= limit) {
                erase()
                throw VaultDestroyed()
            }
            h.put("failedTries", tries)
            saveHeaderJson(h)
            throw WrongPassphrase(triesLeft = limit - tries)
        }
        throw WrongPassphrase()
    }

    private fun promoteShadow(h: JSONObject, shadow: JSONObject, shadowKey: ByteArray) {
        key?.fill(0)
        dir.listFiles()?.forEach { if (it.name != "header.json") it.deleteRecursively() }
        val next = JSONObject()
            .put("v", 1)
            .put("mode", h.getString("mode"))
            .put("salt", shadow.getString("salt"))
            .put("argon2", shadow.getJSONObject("argon2"))
            .put("sealed", shadow.getString("sealed"))
        saveHeaderJson(next)
        key = shadowKey
    }

    fun lock() = synchronized(lockObj) {
        key?.fill(0)
        key = null
    }

    // Deletes everything, and the Keystore key with it.
    fun erase() = synchronized(lockObj) {
        key?.fill(0)
        key = null
        dir.deleteRecursively()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
    }

    fun read(name: String): ByteArray? = synchronized(lockObj) {
        val f = File(dir, name)
        if (!f.isFile) return null
        val k = key ?: return null
        open(k, f.readBytes(), name.toByteArray())
    }

    fun write(name: String, data: ByteArray) = synchronized(lockObj) {
        val k = key ?: return@synchronized
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
    fun append(name: String, record: ByteArray) = synchronized(lockObj) {
        val k = key ?: return@synchronized
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val sealed = seal(k, record, name.toByteArray())
        FileOutputStream(f, true).use { out ->
            DataOutputStream(out).apply { writeInt(sealed.size); write(sealed); flush() }
        }
    }

    fun records(name: String): List<ByteArray> = synchronized(lockObj) {
        val f = File(dir, name)
        if (!f.isFile) return emptyList()
        val k = key ?: return emptyList()
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
        out
    }

    fun rewrite(name: String, records: List<ByteArray>) = synchronized(lockObj) {
        val k = key ?: return@synchronized
        val f = File(dir, name)
        f.parentFile?.mkdirs()
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

    fun delete(name: String) = synchronized(lockObj) {
        File(dir, name).delete()
    }

    private fun writeHeader(mode: Mode, k: ByteArray, passphrase: CharArray?, shadow: JSONObject?, destroyLimit: Int) {
        val h = JSONObject().put("v", 1).put("mode", mode.key)
        var sealed = k.copyOf()
        if (mode.needsPassphrase) {
            val salt = ByteArray(16).also(random::nextBytes)
            val params = JSONObject().put("m", ARGON_MEMORY_KB).put("t", ARGON_PASSES).put("p", 1)
            val kek = argon2(passphrase ?: CharArray(0), salt, params)
            sealed = seal(kek, sealed, AAD_KEY)
            kek.fill(0)
            h.put("salt", b64(salt)).put("argon2", params)
            if (destroyLimit > 0) h.put("destroyLimit", destroyLimit)
            if (shadow != null) h.put("shadow", shadow)
        }
        if (mode.usesDevice) sealed = deviceSeal(sealed)
        h.put("sealed", b64(sealed))
        saveHeaderJson(h)
    }

    private fun saveHeaderJson(h: JSONObject) {
        dir.mkdirs()
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
