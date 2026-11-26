// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class Vault(private val file: File, private val key: () -> SecretKey) {
    private val gson = Gson()
    private val header = byteArrayOf(83, 67, 1)

    fun open(): Profile {
        if (!file.exists()) return Profile(Protocol.generate()).also(::save)
        val bytes = file.readBytes()
        require(bytes.size >= header.size + 12 + 16 && bytes.take(header.size) == header.toList()) { "本机数据库格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, header.size, 12))
        cipher.updateAAD(header)
        val offset = header.size + 12
        val clear = cipher.doFinal(bytes, offset, bytes.size - offset)
        return gson.fromJson(clear.decodeToString(), Profile::class.java)
    }

    fun save(profile: Profile) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(gson.toJson(profile).toByteArray())
        val staged = File.createTempFile("snapshot-", ".tmp", file.parentFile)
        try {
            FileOutputStream(staged).use {
                it.write(header)
                it.write(cipher.iv)
                it.write(sealed)
                it.fd.sync()
            }
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
    }
}

class ChatApp : Application() {
    internal val worker = Executors.newSingleThreadExecutor()
    internal val device: Device by lazy {
        val file = File(noBackupFilesDir, "profile.sealed")
        val vault = Vault(file) { deviceKey(allowCreate = !file.exists()) }
        Device(vault.open(), vault::save)
    }

    private fun deviceKey(allowCreate: Boolean): SecretKey {
        val alias = "secretchat.profile.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "本机密钥不可用" }
        val params = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(params) }.generateKey()
    }
}
