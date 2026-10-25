package com.ibero.museo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewmodels.ViewModelFactoryDsl
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.room.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

// ==========================================
// 1. DATA MODELS & ROOM ENTITIES (SQLite)
// ==========================================

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val email: String,
    val role: String, // "Admin", "Investigador", "Cliente"
    val avatar: String = "👤"
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val targetId: String,
    val title: String,
    val subtitle: String,
    val type: String // "Museum" or "Resource"
)

@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val productId: String,
    val title: String,
    val price: Double,
    var quantity: Int
)

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val orderNumber: String,
    val date: String,
    val itemCount: Int,
    val totalAmount: Double,
    val status: String // "Pendiente", "Confirmado", "Enviado", "Entregado"
)

@Entity(tableName = "qr_history")
data class QrHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val codeScanned: String,
    val title: String,
    val date: String
)

// Remote API Data Classes
data class MuseumDto(
    val id: String,
    val name: String,
    val country: String,
    val city: String,
    val category: String,
    val rating: Double,
    val reviewsCount: Int,
    val description: String,
    val virtualTourUrl: String
)

data class ProductDto(
    val id: String,
    val title: String,
    val category: String, // "Cursos", "Libros", "Materiales"
    val price: Double,
    val description: String,
    val inStock: Boolean
)

data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    val type: String, // "Curso", "Museo", "Pedido", "Publicación"
    val timeAgo: String
)

// ==========================================
// 2. ROOM DATABASE & DAOs
// ==========================================

@Dao
interface AppDao {
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity): Long

    @Query("SELECT * FROM favorites WHERE userId = :userId")
    fun getFavoritesForUser(userId: Int): StateFlow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE userId = :userId AND targetId = :targetId")
    suspend fun removeFavorite(userId: Int, targetId: String)

    @Query("SELECT * FROM favorites WHERE userId = :userId AND targetId = :targetId LIMIT 1")
    suspend fun isFavorite(userId: Int, targetId: String): FavoriteEntity?

    @Query("SELECT * FROM cart_items WHERE userId = :userId")
    fun getCartItems(userId: Int): StateFlow<List<CartItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCartItem(item: CartItemEntity)

    @Query("UPDATE cart_items SET quantity = :quantity WHERE id = :itemId")
    suspend fun updateCartQuantity(itemId: Int, quantity: Int)

    @Query("DELETE FROM cart_items WHERE id = :itemId")
    suspend fun deleteCartItem(itemId: Int)

    @Query("DELETE FROM cart_items WHERE userId = :userId")
    suspend fun clearCart(userId: Int)

    @Query("SELECT * FROM orders WHERE userId = :userId ORDER BY id DESC")
    fun getOrdersForUser(userId: Int): StateFlow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity)

    @Query("UPDATE orders SET status = :status WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Int, status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQrHistory(qrHistory: QrHistoryEntity)
}

@Database(entities = [UserEntity::class, FavoriteEntity::class, CartItemEntity::class, OrderEntity::class, QrHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ibero_museo_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 3. RETROFIT API SERVICE (SPRING BOOT REST MOCK)
// ==========================================

interface RetrofitApiService {
    @GET("api/v1/museums")
    suspend fun getMuseums(): List<MuseumDto>

    @GET("api/v1/products")
    suspend fun getProducts(): List<ProductDto>
}

object RetrofitClient {
    private const val BASE_URL = "https://api.iberomuseo.org/" // Simulated Spring Boot API

    val apiService: RetrofitApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RetrofitApiService::class.java)
    }
}

// Fallback Mock Data Source for REST API Offline Mode
object MockDataRepository {
    fun getMockMuseums(): List<MuseumDto> = listOf(
        MuseumDto("m1", "Museo Nacional de Arte Colonial", "Colombia", "Bogotá", "Historia", 4.8, 120, "Recorre siglos de historia a través de nuestras colecciones de arte colonial Iberoamericano.", "https://virtualtour.com/m1"),
        MuseumDto("m2", "Museo de Arte Moderno", "México", "Ciudad de México", "Arte Moderno", 4.7, 95, "Colección representativa del arte moderno y contemporáneo mexicano e iberoamericano.", "https://virtualtour.com/m2"),
        MuseumDto("m3", "Museo del Prado Virtual", "España", "Madrid", "Clásico", 4.9, 310, "Galerías interactivas y digitalización en alta definición de obras clásicas universales.", "https://virtualtour.com/m3"),
        MuseumDto("m4", "Museo de Arte de Lima (MALI)", "Perú", "Lima", "Arqueología", 4.6, 88, "Colección de más de 17,000 obras de arte peruano precolombino y contemporáneo.", "https://virtualtour.com/m4"),
        MuseumDto("m5", "Museo Nacional de Bellas Artes", "Chile", "Santiago", "Bellas Artes", 4.8, 140, "Fundado en 1880, custodia un valioso patrimonio de bellas artes iberoamericanas.", "https://virtualtour.com/m5")
    )

    fun getMockProducts(): List<ProductDto> = listOf(
        ProductDto("p1", "Curso de Curaduría Virtual", "Cursos", 49.0, "Formación especializada en conservación y museografía digital.", true),
        ProductDto("p2", "Historia del Arte Iberoamericano", "Libros", 35.0, "Un recorrido completo por el desarrollo del arte en Iberoamérica con análisis detallado.", true),
        ProductDto("p3", "Kit de Conservación de Documentos", "Materiales", 120.0, "Materiales profesionales libres de ácido para preservación de archivos.", true),
        ProductDto("p4", "Curso Museos y Comunidad", "Cursos", 39.0, "Estrategias de mediación cultural y desarrollo de audiencias.", true)
    )

    fun getMockNotifications(): List<AppNotification> = listOf(
        AppNotification("n1", "Nuevo curso disponible", "Curso de Museografía e Innovación Digital.", "Curso", "Hace 10 min"),
        AppNotification("n2", "Nuevo museo virtual", "Se ha añadido el Museo de Arte Colonial de Quito.", "Museo", "Hace 1 hora"),
        AppNotification("n3", "Estado de tu pedido", "El pedido #2024-00123 ha sido marcado como Enviado.", "Pedido", "Hace 3 horas"),
        AppNotification("n4", "Nueva publicación disponible", "Descarga el libro 'El Arte Colonial Iberoamericano'.", "Publicación", "Hace 1 día")
    )
}

// ==========================================
// 4. MVVM VIEWMODEL
// ==========================================

class IberoMuseoViewModel(private val dao: AppDao) : ViewModel() {
    var currentUser by mutableStateOf<UserEntity?>(UserEntity(1, "María Fernanda López", "maria@email.com", "Cliente", "👩‍💼"))
        private set

    var museumsList by mutableStateOf<List<MuseumDto>>(emptyList())
        private set

    var productsList by mutableStateOf<List<ProductDto>>(emptyList())
        private set

    var notificationsList by mutableStateOf<List<AppNotification>>(emptyList())
        private set

    var lastScannedResult by mutableStateOf<ProductDto?>(null)
        private set

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            // Attempt to fetch REST API or fallback to mock
            museumsList = try {
                RetrofitClient.apiService.getMuseums()
            } catch (e: Exception) {
                MockDataRepository.getMockMuseums()
            }

            productsList = try {
                RetrofitClient.apiService.getProducts()
            } catch (e: Exception) {
                MockDataRepository.getMockProducts()
            }

            notificationsList = MockDataRepository.getMockNotifications()
        }
    }

    fun loginOrRegister(name: String, email: String, role: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val existing = dao.getUserByEmail(email)
            if (existing != null) {
                currentUser = existing
            } else {
                val newUser = UserEntity(name = name.ifEmpty { "Usuario IberoMuseo" }, email = email, role = role)
                val id = dao.insertUser(newUser)
                currentUser = newUser.copy(id = id.toInt())
            }
            onComplete()
        }
    }

    fun switchRole(role: String) {
        currentUser = currentUser?.copy(role = role) ?: UserEntity(1, "Usuario Demostración", "demo@email.com", role)
    }

    fun toggleFavorite(targetId: String, title: String, subtitle: String, type: String) {
        val user = currentUser ?: return
        viewModelScope.launch {
            val existing = dao.isFavorite(user.id, targetId)
            if (existing != null) {
                dao.removeFavorite(user.id, targetId)
            } else {
                dao.insertFavorite(FavoriteEntity(userId = user.id, targetId = targetId, title = title, subtitle = subtitle, type = type))
            }
        }
    }

    fun addToCart(product: ProductDto, quantity: Int = 1) {
        val user = currentUser ?: return
        viewModelScope.launch {
            dao.insertCartItem(CartItemEntity(userId = user.id, productId = product.id, title = product.title, price = product.price, quantity = quantity))
        }
    }

    fun updateCartQuantity(itemId: Int, newQty: Int) {
        viewModelScope.launch {
            if (newQty <= 0) dao.deleteCartItem(itemId)
            else dao.updateCartQuantity(itemId, newQty)
        }
    }

    fun checkoutCart(country: String, shippingCost: Double, onComplete: () -> Unit) {
        val user = currentUser ?: return
        viewModelScope.launch {
            val orderNum = "#2026-00" + (100..999).random()
            dao.insertOrder(
                OrderEntity(
                    userId = user.id,
                    orderNumber = orderNum,
                    date = "25/10/2026",
                    itemCount = 2,
                    totalAmount = 84.0 + shippingCost,
                    status = "Pendiente"
                )
            )
            dao.clearCart(user.id)
            onComplete()
        }
    }

    fun simulateQrScan(code: String) {
        lastScannedResult = productsList.find { it.id == code } ?: productsList.firstOrNull()
        val user = currentUser ?: return
        viewModelScope.launch {
            dao.insertQrHistory(
                QrHistoryEntity(
                    userId = user.id,
                    codeScanned = code,
                    title = lastScannedResult?.title ?: "Código Desconocido",
                    date = "25/10/2026"
                )
            )
        }
    }

    fun getFavoritesFlow(): StateFlow<List<FavoriteEntity>> {
        val uid = currentUser?.id ?: 1
        return dao.getFavoritesForUser(uid).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    fun getCartFlow(): StateFlow<List<CartItemEntity>> {
        val uid = currentUser?.id ?: 1
        return dao.getCartItems(uid).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    fun getOrdersFlow(): StateFlow<List<OrderEntity>> {
        val uid = currentUser?.id ?: 1
        return dao.getOrdersForUser(uid).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }
}

class IberoMuseoViewModelFactory(private val dao: AppDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IberoMuseoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return IberoMuseoViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// 5. NAVIGATION ROUTES & APP MAIN ACTIVITY
// ==========================================

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Login : Screen("login")
    object Register : Screen("register")
    object Dashboard : Screen("dashboard")
    object VirtualMap : Screen("virtual_map")
    object MuseumDetail : Screen("museum_detail/{id}") {
        fun createRoute(id: String) = "museum_detail/$id"
    }
    object QrScanner : Screen("qr_scanner")
    object QrResult : Screen("qr_result")
    object Catalog : Screen("catalog")
    object ProductDetail : Screen("product_detail/{id}") {
        fun createRoute(id: String) = "product_detail/$id"
    }
    object Favorites : Screen("favorites")
    object Cart : Screen("cart")
    object OrdersHistory : Screen("orders_history")
    object Notifications : Screen("notifications")
    object Profile : Screen("profile")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(applicationContext)
        val viewModelFactory = IberoMuseoViewModelFactory(database.appDao())

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF8C6D46),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFF3EFEA),
                    secondary = Color(0xFF2C3E50),
                    background = Color(0xFFFDFBF7),
                    surface = Color.White
                )
            ) {
                val viewModel: IberoMuseoViewModel = viewModel(factory = viewModelFactory)
                IberoMuseoApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IberoMuseoApp(viewModel: IberoMuseoViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavRoutes = listOf(
        Screen.Dashboard.route,
        Screen.VirtualMap.route,
        Screen.Catalog.route,
        Screen.Notifications.route,
        Screen.Profile.route
    )

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomNavRoutes) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Dashboard.route,
                        onClick = { navController.navigate(Screen.Dashboard.route) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Inicio") },
                        label = { Text("Inicio", fontSize = 10.sp) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.VirtualMap.route,
                        onClick = { navController.navigate(Screen.VirtualMap.route) },
                        icon = { Icon(Icons.Default.Place, contentDescription = "Mapa") },
                        label = { Text("Mapa", fontSize = 10.sp) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Catalog.route,
                        onClick = { navController.navigate(Screen.Catalog.route) },
                        icon = { Icon(Icons.Default.Menu, contentDescription = "Catálogo") },
                        label = { Text("Catálogo", fontSize = 10.sp) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Notifications.route,
                        onClick = { navController.navigate(Screen.Notifications.route) },
                        icon = { Icon(Icons.Default.Notifications, contentDescription = "Notificaciones") },
                        label = { Text("Alertas", fontSize = 10.sp) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Profile.route,
                        onClick = { navController.navigate(Screen.Profile.route) },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
                        label = { Text("Perfil", fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Welcome.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Welcome.route) { WelcomeScreen(navController) }
            composable(Screen.Login.route) { LoginScreen(navController, viewModel) }
            composable(Screen.Register.route) { RegisterScreen(navController, viewModel) }
            composable(Screen.Dashboard.route) { DashboardScreen(navController, viewModel) }
            composable(Screen.VirtualMap.route) { VirtualMapScreen(navController, viewModel) }
            composable(
                Screen.MuseumDetail.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                MuseumDetailScreen(id, navController, viewModel)
            }
            composable(Screen.QrScanner.route) { QrScannerScreen(navController, viewModel) }
            composable(Screen.QrResult.route) { QrResultScreen(navController, viewModel) }
            composable(Screen.Catalog.route) { CatalogScreen(navController, viewModel) }
            composable(
                Screen.ProductDetail.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                ProductDetailScreen(id, navController, viewModel)
            }
            composable(Screen.Favorites.route) { FavoritesScreen(navController, viewModel) }
            composable(Screen.Cart.route) { CartScreen(navController, viewModel) }
            composable(Screen.OrdersHistory.route) { OrdersHistoryScreen(navController, viewModel) }
            composable(Screen.Notifications.route) { NotificationsScreen(navController, viewModel) }
            composable(Screen.Profile.route) { ProfileScreen(navController, viewModel) }
        }
    }
}

// ==========================================
// 6. ALL 15 APPLICATION SCREENS
// ==========================================

// PANTALLA 1: Bienvenida / Inicio
@Composable
fun WelcomeScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🏛️", fontSize = 80.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "IberoMuseo",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Museo de los Museos",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Conecta, aprende y explora el patrimonio cultural e investigativo de Iberoamérica.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = { navController.navigate(Screen.Login.route) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Comenzar", fontSize = 16.sp)
        }
    }
}

// PANTALLA 2: Registro de Usuario
@Composable
fun RegisterScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Crear tu cuenta", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Únete a la comunidad de IberoMuseo", color = Color.Gray)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre completo") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Correo electrónico") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isNotEmpty() && email.isNotEmpty()) {
                    viewModel.loginOrRegister(name, email, "Cliente") {
                        navController.navigate(Screen.Dashboard.route) { popUpTo(Screen.Welcome.route) { inclusive = true } }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Registrarme")
        }
    }
}

// PANTALLA 3: Selección / Inicio de Sesión
@Composable
fun LoginScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    var email by remember { mutableStateOf("maria@email.com") }
    var selectedRole by remember { mutableStateOf("Cliente") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Iniciar sesión", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Selecciona tu rol e ingresa con tus credenciales", color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))

        Text("Tipo de perfil de prueba:", fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Cliente", "Investigador", "Admin").forEach { role ->
                FilterChip(
                    selected = selectedRole == role,
                    onClick = { selectedRole = role },
                    label = { Text(role) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Correo electrónico") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.loginOrRegister("Usuario " + selectedRole, email, selectedRole) {
                    navController.navigate(Screen.Dashboard.route) { popUpTo(Screen.Welcome.route) { inclusive = true } }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Entrar a IberoMuseo")
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = { navController.navigate(Screen.Register.route) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("¿No tienes cuenta? Registrate aquí")
        }
    }
}

// PANTALLA 4: Principal / Dashboard
@Composable
fun DashboardScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val user = viewModel.currentUser

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("¡Hola, ${user?.name ?: "Explorador"}!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Rol: ${user?.role}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { navController.navigate(Screen.QrScanner.route) }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Escanear QR", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Dashboard dynamic content by role
        if (user?.role == "Admin") {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚙️ Panel de Administración", fontWeight = FontWeight.Bold)
                        Text("Revisa solicitudes de compras, estados de pedidos y gestiona notificaciones del sistema.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { navController.navigate(Screen.OrdersHistory.route) }) {
                            Text("Gestionar Solicitudes")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else if (user?.role == "Investigador") {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🔬 Modulo de Investigación", fontWeight = FontWeight.Bold)
                        Text("Registra o edita fichas museológicas y prueba códigos QR vinculados a exposiciones.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { navController.navigate(Screen.VirtualMap.route) }) {
                            Text("Explorar Fichas en Mapa")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item {
            Text("Acceso Rápido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { navController.navigate(Screen.Favorites.route) }, label = { Text("Favoritos") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                AssistChip(onClick = { navController.navigate(Screen.Cart.route) }, label = { Text("Carrito") }, leadingIcon = { Icon(Icons.Default.ShoppingCart, null) })
                AssistChip(onClick = { navController.navigate(Screen.OrdersHistory.route) }, label = { Text("Mis Pedidos") }, leadingIcon = { Icon(Icons.Default.Receipt, null) })
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("Museos Destacados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(viewModel.museumsList) { museum ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { navController.navigate(Screen.MuseumDetail.createRoute(museum.id)) }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏛️", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(museum.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${museum.city}, ${museum.country}", fontSize = 12.sp, color = Color.Gray)
                    }
                    Text("★ ${museum.rating}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// PANTALLA 5: Mapa de Museos Virtuales
@Composable
fun VirtualMapScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    var selectedMuseum by remember { mutableStateOf(viewModel.museumsList.firstOrNull()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Map Area Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFD4E6F1)),
            contentAlignment = Alignment.Center
        ) {
            Text("🗺️ [ Mapa Interactivo de Iberoamérica ]\nMarcadores activos", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        }

        // Bottom Selected Museum Info Card
        selectedMuseum?.let { museum ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(museum.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("📍 ${museum.city}, ${museum.country}", color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(museum.description, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { navController.navigate(Screen.MuseumDetail.createRoute(museum.id)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ver Detalle y Visita Virtual")
                    }
                }
            }
        }
    }
}

// PANTALLA 6: Detalle del Museo Virtual
@Composable
fun MuseumDetailScreen(museumId: String, navController: NavController, viewModel: IberoMuseoViewModel) {
    val museum = viewModel.museumsList.find { it.id == museumId } ?: viewModel.museumsList.first()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("🏛️ ${museum.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("📍 ${museum.city}, ${museum.country} | Categoría: ${museum.category}", color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("Imagen del Museo", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(museum.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { /* Abre link de recorrido virtual */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Iniciar Visita Virtual")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                viewModel.toggleFavorite(museum.id, museum.name, museum.country, "Museum")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FavoriteBorder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Guardar en Favoritos")
        }
    }
}

// PANTALLA 7: Escáner QR
@Composable
fun QrScannerScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Escáner de Código QR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Enfoca el código QR de la exposición o publicación", color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("📷 Camara Activa\n[ Encuadre QR ]", color = Color.White, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                viewModel.simulateQrScan("p2") // Simula lectura del producto p2
                navController.navigate(Screen.QrResult.route)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simular Lectura de QR Exitoso")
        }
    }
}

// PANTALLA 8: Resultado del QR
@Composable
fun QrResultScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val result = viewModel.lastScannedResult

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✅ ¡Código QR Identificado!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(result?.title ?: "Elemento Museológico", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Categoría: ${result?.category ?: "Publicación"}", color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Text(result?.description ?: "Descripción completa del contenido del código escaneado.")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { navController.navigate(Screen.ProductDetail.createRoute(result?.id ?: "p1")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ver Ficha Completa")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Volver a Escanear")
        }
    }
}

// PANTALLA 9: Catálogo de Productos y Cursos
@Composable
fun CatalogScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    var selectedCategory by remember { mutableStateOf("Todos") }
    val categories = listOf("Todos", "Cursos", "Libros", "Materiales")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Catálogo IberoMuseo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val filtered = if (selectedCategory == "Todos") viewModel.productsList else viewModel.productsList.filter { it.category == selectedCategory }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { product ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.ProductDetail.createRoute(product.id)) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📦", fontSize = 32.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(product.title, fontWeight = FontWeight.Bold, maxLines = 2, fontSize = 14.sp)
                        Text("US$ ${product.price}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// PANTALLA 10: Detalle y Solicitud de Compra
@Composable
fun ProductDetailScreen(productId: String, navController: NavController, viewModel: IberoMuseoViewModel) {
    val product = viewModel.productsList.find { it.id == productId } ?: viewModel.productsList.first()
    var quantity by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(product.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("US$ ${product.price}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Text(product.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cantidad:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = { if (quantity > 1) quantity-- }) { Text("-", fontSize = 20.sp) }
            Text("$quantity", fontWeight = FontWeight.Bold)
            IconButton(onClick = { quantity++ }) { Text("+", fontSize = 20.sp) }
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                viewModel.addToCart(product, quantity)
                navController.navigate(Screen.Cart.route)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Agregar al Carrito")
        }
    }
}

// PANTALLA 11: Colección de Museos Favoritos
@Composable
fun FavoritesScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val favorites by viewModel.getFavoritesFlow().collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Mis Favoritos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (favorites.isEmpty()) {
            Text("No tienes elementos en tu lista de favoritos aún.", color = Color.Gray)
        } else {
            LazyColumn {
                items(favorites) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (item.type == "Museum") "🏛️" else "📚", fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold)
                                Text(item.subtitle, fontSize = 12.sp, color = Color.Gray)
                            }
                            IconButton(onClick = { viewModel.toggleFavorite(item.targetId, "", "", "") }) {
                                Icon(Icons.Default.Favorite, contentDescription = "Eliminar", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

// PANTALLA 12: Carrito y Cálculo de Envío
@Composable
fun CartScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val cartItems by viewModel.getCartFlow().collectAsState()
    var destinationCountry by remember { mutableStateOf("Chile") }
    val shippingCost = if (destinationCountry == "Chile") 5.0 else 15.0
    val subtotal = cartItems.sumOf { it.price * it.quantity }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Mi Carrito", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(cartItems) { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            Text("US$ ${item.price} x ${item.quantity}", color = Color.Gray)
                        }
                        IconButton(onClick = { viewModel.updateCartQuantity(item.id, item.quantity - 1) }) { Text("-") }
                        IconButton(onClick = { viewModel.updateCartQuantity(item.id, item.quantity + 1) }) { Text("+") }
                    }
                }
            }
        }

        Divider()
        Spacer(modifier = Modifier.height(12.dp))
        Text("Cálculo de Envío Ficticio:", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Chile", "Colombia", "España", "México").forEach { country ->
                FilterChip(
                    selected = destinationCountry == country,
                    onClick = { destinationCountry = country },
                    label = { Text(country) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Subtotal: US$ $subtotal")
        Text("Envío Estimado: US$ $shippingCost")
        Text("Total Final: US$ ${subtotal + shippingCost}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.checkoutCart(destinationCountry, shippingCost) {
                    navController.navigate(Screen.OrdersHistory.route)
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Proceder al Pago / Confirmar Solicitud")
        }
    }
}

// PANTALLA 13: Historial y Gestión de Solicitudes
@Composable
fun OrdersHistoryScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val orders by viewModel.getOrdersFlow().collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Historial de Solicitudes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (orders.isEmpty()) {
            Text("No hay solicitudes registradas.", color = Color.Gray)
        } else {
            LazyColumn {
                items(orders) { order ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(order.orderNumber, fontWeight = FontWeight.Bold)
                                Text(order.status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text("Fecha: ${order.date} | Cantidad: ${order.itemCount} items", fontSize = 12.sp, color = Color.Gray)
                            Text("Monto Total: US$ ${order.totalAmount}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// PANTALLA 14: Notificaciones
@Composable
fun NotificationsScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Centro de Notificaciones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(viewModel.notificationsList) { notif ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(notif.title, fontWeight = FontWeight.Bold)
                            Text(notif.timeAgo, fontSize = 10.sp, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(notif.message, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// PANTALLA 15: Perfil y Configuración
@Composable
fun ProfileScreen(navController: NavController, viewModel: IberoMuseoViewModel) {
    val user = viewModel.currentUser

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(user?.avatar ?: "👤", fontSize = 48.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(user?.name ?: "Usuario", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(user?.email ?: "", color = Color.Gray)
                Text("Rol Actual: ${user?.role}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Cambiar Rol (Modo Académico de Prueba):", fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Cliente", "Investigador", "Admin").forEach { role ->
                OutlinedButton(onClick = { viewModel.switchRole(role) }) {
                    Text(role)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                navController.navigate(Screen.Welcome.route) { popUpTo(0) }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerrar Sesión")
        }
    }
}