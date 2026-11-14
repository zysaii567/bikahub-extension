package eu.kanade.tachiyomi.extension.zh.bikahub

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesImageInterceptor : Interceptor {
    private val key = SecretKeySpec("f5d965df75336270".toByteArray(StandardCharsets.UTF_8), "AES")
    private val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(StandardCharsets.UTF_8))

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()

        if (!response.isSuccessful || !url.contains("pic.drleao.cn")) {
            return response
        }

        val encryptedBytes = response.body.bytes()
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decryptedBytes = cipher.doFinal(encryptedBytes)

            response.newBuilder()
                .body(decryptedBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        } catch (_: Exception) {
            response.newBuilder()
                .body(encryptedBytes.toResponseBody(response.body.contentType()))
                .build()
        }
    }
}

