package com.aidotble.widget.ble

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives for the AiDot / Leedarson BLE protocol.
 *
 * Direct port of aidot_control.py (verified against real hardware):
 *   - provisioning read/write key (chars a00a/a00b/a00c)
 *   - runtime command-channel key (chars b001/b002)
 *   - AES-256-CTR, the one cipher both channels use.
 */
object Crypto {

    /** Hard-coded sign-in nonce / IV (also the plaintext signed during sign-in). */
    val SIGN_CONST: ByteArray = "nghx8qug3lkl5oyd".toByteArray(Charsets.US_ASCII)

    private const val PROV_PREFIX = "DF123EFA234569823098"

    /**
     * Base MAC = BLE address with the last octet - 2 (classic ESP base-vs-BLE offset);
     * equals the MAC shown in the advertised name / manufacturer data.
     * "AA:BB:CC:DD:EE:F2" -> "AABBCCDDEEF0".
     */
    fun baseMac(bleAddr: String): String {
        val bytes = bleAddr.replace(":", "").chunked(2).map { it.toInt(16) }.toIntArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] - 2) and 0xFF
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /** AES-256 key for the provisioning read/write chars (a00a/a00b/a00c). */
    fun provKey(bleAddr: String): ByteArray {
        val s = (PROV_PREFIX + baseMac(bleAddr)).toByteArray(Charsets.US_ASCII)
        return s.copyOf(32) // truncate to 32, right-pad with 0x00 if shorter
    }

    /**
     * AES-256 key for the runtime command channel (b001/b002).
     *
     * k = sha256("modelId,macLower,deviceId,nghx8qug3lkl5oyd")[:16].hex()
     * The 32 lowercase hex characters are used as the raw 32 key bytes.
     */
    fun commandKey(modelId: String, bleAddr: String, deviceId: String): ByteArray {
        val mac = baseMac(bleAddr).lowercase()
        val material = "$modelId,$mac,$deviceId,nghx8qug3lkl5oyd"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.US_ASCII))
        val hex = digest.copyOf(16).joinToString("") { "%02x".format(it) } // 32 hex chars
        return hex.toByteArray(Charsets.US_ASCII) // used as 32 raw key bytes
    }

    /** AES-256-CTR (Java "AES/CTR/NoPadding"); encrypt == decrypt for a CTR keystream. */
    fun ctr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Provisioning read: [16-byte ASCII nonce IV | AES-256-CTR ciphertext]. */
    fun decryptRead(raw: ByteArray, key: ByteArray): ByteArray =
        ctr(key, raw.copyOfRange(0, 16), raw.copyOfRange(16, raw.size))
}
