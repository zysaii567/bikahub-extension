package eu.kanade.tachiyomi.extension.zh.manwa3

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Manwa3ImageInterceptor : Interceptor {

    private val keyBytes = "0B6666A0-BB59-1381-B746-a0E4C9AC".toByteArray(StandardCharsets.UTF_8).let {
        Arrays.copyOf(it, 32)
    }
    private val secretKey = SecretKeySpec(keyBytes, "AES")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val url = request.url.toString()
        if (!response.isSuccessful || !url.contains("/en_images/")) {
            return response
        }

        val body = response.body
        val bytes = body.bytes()

        if (bytes.size < 16) {
            return response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }

        // Check if already an unencrypted image (JPEG, PNG, GIF, WEBP)
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val isPlainImage = (b0 == 0xFF && b1 == 0xD8) || // JPEG
            (b0 == 0x89 && b1 == 0x50) || // PNG
            (b0 == 0x47 && b1 == 0x49) || // GIF
            (b0 == 0x52 && b1 == 0x49) // RIFF (WEBP)

        if (isPlainImage) {
            return response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }

        return try {
            val iv = Arrays.copyOfRange(bytes, 0, 16)
            val ciphertext = Arrays.copyOfRange(bytes, 16, bytes.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)

            val d0 = decrypted[0].toInt() and 0xFF
            val d1 = decrypted[1].toInt() and 0xFF
            val mediaType = when {
                d0 == 0xFF && d1 == 0xD8 -> "image/jpeg"
                d0 == 0x89 && d1 == 0x50 -> "image/png"
                d0 == 0x52 && d1 == 0x49 -> "image/webp"
                else -> "image/jpeg"
            }.toMediaType()

            response.newBuilder()
                .body(decrypted.toResponseBody(mediaType))
                .build()
        } catch (_: Exception) {
            response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }
    }
}
