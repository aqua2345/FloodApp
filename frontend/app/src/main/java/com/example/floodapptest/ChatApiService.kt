import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class ChatRequest(
    val message: String,
    val chat_id: String,
    val lat: Double? = null,
    val lon: Double? = null
)

// ── Данные маршрута ──────────────────────────────────────────────────────────

data class UserLocation(
    val lat: Double,
    val lon: Double
)

data class PvrDestination(
    val name: String,
    val address: String,
    val district: String,
    val lat: Double,
    val lon: Double,
    val distance_km: Double,
    val phone: String = "",
    val capacity: Int = 0
)

data class RouteData(
    val type: String,                       // "pvr_selection" | "route_map"
    val user_location: UserLocation,
    val destinations: List<PvrDestination>
)

// ── Ответ чат-бота ───────────────────────────────────────────────────────────

data class ChatResponse(
    val response: String,
    val route_data: RouteData? = null       // null для обычных сообщений
)

// ── Retrofit ─────────────────────────────────────────────────────────────────

interface ChatApiService {
    @POST("/chat")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse
}

object RetrofitClient {
    // private const val BASE_URL = "http://192.168.1.6:8000"
    private const val BASE_URL = "http://139.100.225.160:8000"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(490, TimeUnit.SECONDS)
        .readTimeout(490, TimeUnit.SECONDS)
        .build()

    val apiService: ChatApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatApiService::class.java)
    }
}