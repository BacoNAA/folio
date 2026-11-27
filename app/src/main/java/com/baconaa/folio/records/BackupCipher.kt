package com.baconaa.folio.records

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Version 1: salted PBKDF2, independent authenticated 64 KiB frames and a mandatory authenticated end frame. */
internal object BackupCipher {
    private val magic = "FOLIOENC".toByteArray(Charsets.US_ASCII)
    private const val chunk = 64 * 1024

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { return SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun frame(mode: Int, key: SecretKeySpec, header: ByteArray, index: Int, size: Int, bytes: ByteArray, count: Int): ByteArray {
        check(index >= 0) { "备份超出文件格式容量。" }
        val nonce = ByteBuffer.allocate(12).put(header, header.size - 8, 8).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(4).putInt(size).array())
            doFinal(bytes, 0, count)
        }
    }

    suspend fun encrypt(source: InputStream, target: OutputStream, password: CharArray) {
        require(password.size >= 8) { "请使用至少八个字符的密码。" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = magic + byteArrayOf(1) + salt + prefix
        val secret = key(password, salt)
        val buffer = ByteArray(chunk)
        val output = DataOutputStream(target)
        output.write(header)
        var index = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = source.read(buffer).coerceAtLeast(0)
            output.writeInt(n)
            output.write(frame(Cipher.ENCRYPT_MODE, secret, header, index++, n, buffer, n))
            if (n == 0) break
        }
        output.flush()
        buffer.fill(0)
    }

    suspend fun decrypt(source: InputStream, target: OutputStream, password: CharArray, byteBudget: Long) {
        val input = DataInputStream(source.buffered())
        val header = ByteArray(magic.size + 1 + 16 + 8).also(input::readFully)
        require(header.copyOf(magic.size).contentEquals(magic) && header[magic.size] == 1.toByte()) { "未识别的备份版本。" }
        val salt = header.copyOfRange(magic.size + 1, magic.size + 17)
        val secret = key(password, salt)
        var index = 0
        var remaining = byteBudget
        val encrypted = ByteArray(chunk + 16)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.readInt()
            require(n in 0..chunk) { "备份分段损坏。" }
            require(n <= remaining) { "手机没有足够的预检空间。" }
            remaining -= n
            input.readFully(encrypted, 0, n + 16)
            val clear = try { frame(Cipher.DECRYPT_MODE, secret, header, index++, n, encrypted, n + 16) }
                catch (failure: AEADBadTagException) { throw IOException("密码错误或备份文件已损坏。", failure) }
            target.write(clear)
            clear.fill(0)
            if (n == 0) break
        }
        require(input.read() == -1) { "备份末尾包含多余数据。" }
    }
}
