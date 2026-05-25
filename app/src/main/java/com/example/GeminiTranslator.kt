package com.example

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// --- Gemini API Moshi Models ---

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<ContentJson>,
    val generationConfig: GenerationConfigJson? = null
)

@JsonClass(generateAdapter = true)
data class ContentJson(
    val parts: List<PartJson>
)

@JsonClass(generateAdapter = true)
data class PartJson(
    val text: String? = null,
    val inlineData: InlineDataJson? = null
)

@JsonClass(generateAdapter = true)
data class InlineDataJson(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfigJson(
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<CandidateJson>?
)

@JsonClass(generateAdapter = true)
data class CandidateJson(
    val content: ContentJson?
)

// --- Retrofit Api Interface ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

// --- Retrofit Network Client ---

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

// --- Translation Logic Helper ---

object GeminiTranslator {

    /**
     * Converts a Bitmap to a Base64 string for multimodal inputs.
     */
    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Compress to JPEG with 75% quality to optimize bandwidth and tokens
        this.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Sends a screenshot Bitmap to Gemini 3.5 Flash along with translation instructions.
     */
    suspend fun translateScreenImage(bitmap: Bitmap, targetLanguage: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        
        // Check if the API key is empty or the default placeholder
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") {
            return@withContext "Error: Gemini API Key is missing.\n\nPlease define 'GEMINI_API_KEY' in the Secrets panel in the AI Studio UI to enable real-time screen translations."
        }

        val prompt = """
            You are a translation assistant operating on behalf of an Android on-screen screen translator.
            1. Analyze the provided mobile phone screen screenshot.
            2. Detect any text on the screen that is not in the '$targetLanguage' language.
            3. Translate that foreign text into '$targetLanguage'.
            4. Keep the translation concise, naturally grouped by paragraphs, lists, or headers representing the visual structure.
            5. If all text is already in '$targetLanguage', output a message stating that everything is already in '$targetLanguage'.
            6. If there is absolutely no text on the screen, output 'No readable text was detected on the screen.'
            7. PROVIDE ONLY THE TRANSLATED TEXT. Do not explain the image or write developer comments. Output the polished translate result.
        """.trimIndent()

        val base64Image = try {
            bitmap.toBase64()
        } catch (e: Exception) {
            return@withContext "Error: Failed to process screen image. Please try again."
        }

        val request = GenerateContentRequest(
            contents = listOf(
                ContentJson(
                    parts = listOf(
                        PartJson(text = prompt),
                        PartJson(inlineData = InlineDataJson(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfigJson(temperature = 0.3f)
        )

        try {
            val response = GeminiClient.service.generateContent(apiKey, request)
            val translatedText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            translatedText ?: "Error: Received empty response from Gemini API."
        } catch (e: Exception) {
            e.printStackTrace()
            "Error calling Gemini API: ${e.localizedMessage ?: e.message ?: "Unknown Connection Error"}"
        }
    }
}
