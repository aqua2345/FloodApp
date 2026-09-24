package com.example.floodapptest

import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.floodapptest.ui.theme.FloodAppTestTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch

val BgGray = Color(0xFFF8F9FA)
val InputGray = Color(0xFFE9ECEF)
val TextDarkGray = Color(0xFF495057)
val BotIconBg = Color(0xFF212529)
val AppWhite = Color(0xFFFFFFFF)
val AppLightGray = Color(0xFFF5F5F5)
val AppTextMain = Color(0xFF757575)
val AppHeaderGray = Color(0xFF616161)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FloodAppTestTheme {
                // Проверяем, был ли просмотрен онбординг
                val isOnboardingCompleted = remember {
                    OnboardingPreferences.isOnboardingCompleted(this)
                }

                var currentScreen by remember {
                    mutableStateOf(if (isOnboardingCompleted) "chat" else "onboarding")
                }

                when (currentScreen) {
                    "onboarding" -> OnboardingScreen(
                        onFinish = {
                            OnboardingPreferences.setOnboardingCompleted(this)
                            currentScreen = "chat"
                        }
                    )
                    "chat" -> ChatScreen(
                        onNavigateToMap = { currentScreen = "map" },
                        onNavigateToInstructions = { currentScreen = "instructions" },
                        onNavigateToProfile = { currentScreen = "onboarding" }
                    )
                    "map" -> MapScreen(onBack = { currentScreen = "chat" })
                    "instructions" -> InstructionsScreen(onBack = { currentScreen = "chat" })
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToMap: () -> Unit,
    onNavigateToInstructions: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application)
    )

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isLoading by viewModel.isLoading
    var menuExpanded by remember { mutableStateOf(false) }

    // ✨ Состояние для текстового ввода (вынесено наверх для доступа из голосового ввода)
    var inputText by remember { mutableStateOf("") }

    // ✨ ГОЛОСОВОЙ ВВОД - Speech Recognition
    // ✨ ГОЛОСОВОЙ ВВОД - Speech Recognition
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = results?.firstOrNull()

            if (!recognizedText.isNullOrBlank()) {
                // Выводим Toast для обратной связи пользователю
                Toast.makeText(context, "Распознано: $recognizedText", Toast.LENGTH_SHORT).show()

                // АВТОМАТИЧЕСКАЯ ОТПРАВКА: Сразу передаем текст во ViewModel
                viewModel.sendQuestion(recognizedText)

                // Очищаем текстовое поле ввода (на случай, если там что-то было)
                inputText = ""
            } else {
                Toast.makeText(context, "Не удалось распознать речь", Toast.LENGTH_SHORT).show()
            }
        }
        // Если пользователь отменил ввод (нажал "Назад" или закрыл диалог),
        // resultCode будет равен RESULT_CANCELED, и код сюда просто не зайдет.
    }

    // Функция запуска голосового ввода
    fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите ваш вопрос...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_SHORT).show()
        }
    }

    // ЛОГИКА ГЕОЛОКАЦИИ
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    loc?.let {
                        viewModel.lastLat = it.latitude
                        viewModel.lastLon = it.longitude
                    }
                }
        }
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    loc?.let {
                        viewModel.lastLat = it.latitude
                        viewModel.lastLon = it.longitude
                    }
                }
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ✨ ИНИЦИАЛИЗАЦИЯ TEXT-TO-SPEECH (Озвучка)
    // Внутри ChatScreen, после переменных viewModel
    // ✨ СОСТОЯНИЕ ОЗВУЧКИ
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var currentlySpeakingText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        // Создаем экземпляр и сразу настраиваем его
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Используем tts (переменную из remember), так как она будет обновлена ниже
            }
        }.also { instance ->
            instance.language = Locale("ru", "RU")
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    currentlySpeakingText = null
                }
                override fun onError(utteranceId: String?) {
                    currentlySpeakingText = null
                }
            })
        }

        tts = textToSpeech

        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    val toggleSpeak: (String) -> Unit = { text ->
        if (currentlySpeakingText == text) {
            tts?.stop()
            currentlySpeakingText = null
        } else {
            currentlySpeakingText = text
            val params = Bundle()
            // Параметр utterance_id нужен для работы коллбэков (смены иконки)
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "msg_id")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "msg_id")
        }
    }

    // Функция, которую мы будем вызывать по клику на кнопку
    val speakText: (String) -> Unit = { text ->
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.White,
                modifier = Modifier.fillMaxHeight().width(320.dp)
            ) {
                ChatDrawerContent(
                    viewModel = viewModel,
                    onChatSelected = { id ->
                        viewModel.loadMessages(id)
                        scope.launch { drawerState.close() }
                    },
                    onNewChatClick = {
                        viewModel.createNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onMapClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToMap()
                    },
                    onInstructionsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToInstructions()
                    },
                    onProfileClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToProfile()
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = AppWhite,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = AppWhite),
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu", tint = AppTextMain, modifier = Modifier.size(32.dp))
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Удалить чат", color = Color.Red) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.currentChatId?.let { viewModel.deleteChat(it) }
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                            )
                        }
                    }
                )
            },
            bottomBar = {
                ChatInputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSendClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendQuestion(inputText)
                            inputText = ""
                        }
                    },
                    onStopClick = {
                        viewModel.cancelGeneration()
                    },
                    onVoiceClick = { startVoiceInput() }, // ✨ Добавлен голосовой ввод
                    isLoading = isLoading,
                    enabled = !isLoading
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                // Внутри Scaffold -> Box
                val messages = viewModel.currentMessages
                if (messages.isEmpty()) {
                    EmptyStateDesign(onSuggestionClick = { suggestion -> viewModel.sendQuestion(suggestion) })
                } else {
                    MessageList(
                        messages = messages,
                        onSpeak = toggleSpeak,
                        currentlySpeakingText = currentlySpeakingText
                    )
                }
            }
        }
    }
}

@Composable
fun ChatDrawerContent(
    viewModel: ChatViewModel,
    onChatSelected: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onMapClick: () -> Unit,
    onInstructionsClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val sessionList = viewModel.sessions.values.toList().sortedByDescending { it.id }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("История чатов", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessionList) { session ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onChatSelected(session.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (viewModel.currentChatId == session.id) InputGray else BgGray,
                    border = BorderStroke(1.dp, Color.LightGray)
                ) {
                    Text(session.title, modifier = Modifier.padding(12.dp), maxLines = 1, fontSize = 14.sp)
                }
            }
        }

        OutlinedButton(
            onClick = onNewChatClick,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = BgGray)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Новый чат", color = Color.Black)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.AddCircleOutline, null, tint = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DrawerBottomItem(icon = Icons.Outlined.Map, text = "Карта", onClick = onMapClick)
            DrawerBottomItem(icon = Icons.Default.Description, text = "Инструкции", onClick = onInstructionsClick)
            DrawerBottomItem(icon = Icons.Outlined.AccountCircle, text = "Профиль", onClick = onProfileClick)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun DrawerBottomItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Icon(icon, text, modifier = Modifier.size(28.dp), tint = Color.Black)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text, fontSize = 12.sp, color = Color.Black)
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    onSpeak: (String) -> Unit,
    currentlySpeakingText: String?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(messages) { message ->
            when (message) {
                is ChatMessage.Text -> {
                    if (message.isUser) {
                        UserMessageItem(message)
                    } else {
                        BotMessageItem(message, onSpeak, currentlySpeakingText)
                    }
                }
                is ChatMessage.Route -> {
                    RouteMessageItem(message)
                }
            }
        }
    }
}

@Composable
fun UserMessageItem(message: ChatMessage.Text) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(message.text, color = TextDarkGray, modifier = Modifier.padding(end = 8.dp, top = 8.dp).weight(1f, false))
        }
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, tint = Color.White)
        }
    }
}

@Composable
fun BotMessageItem(
    message: ChatMessage.Text,
    onSpeak: (String) -> Unit,
    currentlySpeakingText: String?
) {
    val context = LocalContext.current
    val isSpeaking = currentlySpeakingText == message.text
    val annotatedText = parseBotResponse(message.text)

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BotIconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SmartToy, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // Иконка Озвучки с переключением
                Icon(
                    imageVector = if (isSpeaking) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = "Озвучка",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onSpeak(message.text) },
                    tint = if (isSpeaking) Color(0xFF3B82F6) else Color.Gray
                )

                Spacer(Modifier.width(12.dp))

                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Копировать",
                    modifier = Modifier.size(18.dp).clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Chat Message", message.text))
                        Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
                    },
                    tint = Color.Gray
                )

                Spacer(Modifier.width(12.dp))

                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Поделиться",
                    modifier = Modifier.size(18.dp).clickable {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message.text)
                        }
                        context.startActivity(Intent.createChooser(intent, "Поделиться через"))
                    },
                    tint = Color.Gray
                )
            }

            SelectionContainer {
                ClickableText(
                    text = annotatedText,
                    style = LocalTextStyle.current.copy(
                        color = TextDarkGray,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    onClick = { offset ->
                        annotatedText.getStringAnnotations("PHONE", offset, offset).firstOrNull()?.let {
                            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${it.item}")
                            })
                            return@ClickableText
                        }
                        annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(it.item)
                            })
                        }
                    }
                )
            }
        }
    }
}

// Функция для копирования текста в буфер обмена
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Сгенерированный ответ", text)
    clipboard.setPrimaryClip(clip)
}

// Функция для открытия стандартного диалога "Поделиться"
fun shareText(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Поделиться через")
    context.startActivity(shareIntent)
}

@Composable
fun parseBotResponse(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        // Телефоны: +7/8 стандарт | экстренные 1XX | местный XX-XX-XX | нестандартный X(XXXXX)X-XX-XX
        val phoneRegex = ("(?:(?:\\+7|8)[\\s\\-]?\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2})" +
                "|(?:\\b1\\d{2}\\b)" +
                "|(?:\\b\\d{2}[\\-]\\d{2}[\\-]\\d{2}\\b)" +
                "|(?:\\b\\d\\(\\d{5}\\)\\d[\\-]\\d{2}[\\-]\\d{2}\\b)").toRegex()
        // URL: http/https ссылки
        val urlRegex = "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+".toRegex()

        lines.forEachIndexed { index, line ->
            val lineStartIndex = length
            var lineContentForRegex = ""

            if (line.trim().startsWith("###")) {
                val h = line.replace("#", "").trim()
                lineContentForRegex = h
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)) { append(h) }
            } else {
                val boldRegex = "\\*\\*(.*?)\\*\\*".toRegex()
                var lastIdx = 0
                boldRegex.findAll(line).forEach { m ->
                    val b = line.substring(lastIdx, m.range.first)
                    append(b); lineContentForRegex += b
                    val bt = m.groupValues[1]
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF212529))) { append(bt) }
                    lineContentForRegex += bt
                    lastIdx = m.range.last + 1
                }
                val a = line.substring(lastIdx)
                append(a); lineContentForRegex += a
            }

            phoneRegex.findAll(lineContentForRegex).forEach { m ->
                val s = lineStartIndex + m.range.first
                val e = lineStartIndex + m.range.last + 1
                addStyle(SpanStyle(color = Color(0xFF007AFF), textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium), s, e)
                addStringAnnotation("PHONE", m.value.replace(Regex("[^\\d+]"), ""), s, e)
            }
            urlRegex.findAll(lineContentForRegex).forEach { m ->
                val s = lineStartIndex + m.range.first
                val e = lineStartIndex + m.range.last + 1
                addStyle(SpanStyle(color = Color(0xFF007AFF), textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium), s, e)
                addStringAnnotation("URL", m.value, s, e)
            }
            if (index < lines.size - 1) append("\n")
        }
    }
}

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    onVoiceClick: () -> Unit, // ✨ Новый параметр для голосового ввода
    isLoading: Boolean,
    enabled: Boolean
) {
    // Создаём бесконечную анимацию для плавного движения цветов
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")

    // Плавная анимация от 0 до 1
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing), // 4 секунды
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    // Базовая синяя палитра
    val baseBlueColors = listOf(
        Color(0xFF1E40AF), // Тёмно-синий
        Color(0xFF2563EB), // Синий
        Color(0xFF3B82F6), // Яркий синий
        Color(0xFF60A5FA), // Средне-голубой
        Color(0xFF93C5FD), // Светло-голубой
        Color(0xFFBAE6FD), // Очень светлый голубой
        Color(0xFF7C3AED), // Фиолетово-синий
        Color(0xFF6366F1), // Индиго
        Color(0xFF4F46E5)  // Тёмный индиго
    )

    // Функция для интерполяции цветов
    fun lerp(start: Color, end: Color, fraction: Float): Color {
        return Color(
            red = start.red + (end.red - start.red) * fraction,
            green = start.green + (end.green - start.green) * fraction,
            blue = start.blue + (end.blue - start.blue) * fraction,
            alpha = start.alpha + (end.alpha - start.alpha) * fraction
        )
    }

    // Создаём плавно сдвигающийся список цветов
    val animatedColors = remember(progress) {
        val shift = progress * baseBlueColors.size
        val colors = mutableListOf<Color>()

        // Создаём больше промежуточных цветов для плавности
        for (i in 0..50) {
            val position = (i / 50f * baseBlueColors.size + shift) % baseBlueColors.size
            val index = position.toInt()
            val nextIndex = (index + 1) % baseBlueColors.size
            val fraction = position - index

            colors.add(lerp(baseBlueColors[index], baseBlueColors[nextIndex], fraction))
        }
        colors.add(colors.first()) // Замыкаем круг
        colors
    }

    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isLoading) {
                            Modifier.drawWithContent {
                                drawContent()

                                // Рисуем рамку с плавно движущимся градиентом
                                drawRoundRect(
                                    brush = Brush.sweepGradient(
                                        colors = animatedColors,
                                        center = center
                                    ),
                                    style = Stroke(width = 7f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        50.dp.toPx()
                                    )
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Задайте вопрос...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    enabled = enabled,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputGray,
                        unfocusedContainerColor = InputGray,
                        disabledContainerColor = InputGray,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent
                    ),
                    leadingIcon = {
                        // ✨ Кнопка микрофона для голосового ввода
                        IconButton(
                            onClick = onVoiceClick,
                            enabled = enabled && !isLoading // Активна только когда не идет загрузка
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Голосовой ввод",
                                tint = if (enabled && !isLoading) Color(0xFF3B82F6) else Color.Gray
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (isLoading) {
                                    onStopClick()
                                } else {
                                    onSendClick()
                                }
                            }
                        ) {
                            if (isLoading) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Остановить генерацию",
                                    tint = Color(0xFF3B82F6)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Отправить"
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun EmptyStateDesign(onSuggestionClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(
            painter = painterResource(id = R.drawable.utmn_logo),
            contentDescription = "Логотип университета",
            modifier = Modifier
                .height(128.dp)
                .padding(bottom = 12.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
        Text("Паводок72", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = AppHeaderGray, modifier = Modifier.padding(bottom = 40.dp))
        val suggestions = listOf("Что делать при паводке?", "Какие документы взять при эвакуации?", "Первая помощь пострадавшему в воде", "Построй мне маршрут эвакуации!")
        suggestions.forEach { text ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onSuggestionClick(text) },
                color = AppLightGray, shape = RoundedCornerShape(16.dp)
            ) {
                Text(text, modifier = Modifier.padding(20.dp), textAlign = TextAlign.Center, color = AppTextMain, fontSize = 16.sp)
            }
        }
    }
}