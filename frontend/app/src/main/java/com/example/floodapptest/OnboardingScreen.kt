package com.example.floodapptest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Цвета для онбординга
private val PrimaryBlue = Color(0xFF3B82F6)
private val LightBlue = Color(0xFF93C5FD)
private val DarkBlue = Color(0xFF1E40AF)
private val BackgroundGray = Color(0xFFF8F9FA)
private val TextGray = Color(0xFF495057)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 5 }) // 1 экран приветствия + 4 слайда
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BackgroundGray,
                        Color.White
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Пейджер с контентом
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> OnboardingPage(
                        icon = Icons.Default.SmartToy,
                        title = "Ваш интеллектуальный гид «Паводок72»",
                        description = "Я — умный ассистент, который знает всё о паводковой ситуации в Тюменской области. Я анализирую ваше местоположение и даю советы, актуальные именно для вашего района.",
                        features = listOf(
                            FeatureItem(Icons.Outlined.Chat, "Общайтесь в чате как с живым человеком"),
                            FeatureItem(Icons.Default.Mic, "Используйте голосовой ввод — я отлично понимаю речь")
                        )
                    )
                    2 -> OnboardingPage(
                        icon = Icons.Default.CheckCircle,
                        title = "Задавайте любые вопросы",
                        description = "Не знаете, что делать? Спросите меня! Я подскажу, как подготовить дом, какие документы собрать и где находятся ближайшие пункты временного размещения (ПВР).",
                        features = listOf(
                            FeatureItem(Icons.Outlined.LocationOn, "«Где ближайший пункт эвакуации?»"),
                            FeatureItem(Icons.Outlined.Description, "«Какие дороги перекрыты в Ишиме?»"),
                            FeatureItem(Icons.Outlined.Description, "«Составь список вещей для эвакуации»"),
                            FeatureItem(Icons.Outlined.LocationOn, "«Построй безопасный маршрут»")
                        )
                    )
                    3 -> OnboardingPage(
                        icon = Icons.Default.Map,
                        title = "Визуальный контроль обстановки",
                        description = "В приложении доступна живая карта с официального геопортала Тюменской области. Следите за уровнем воды и зонами затопления в режиме реального времени.",
                        features = listOf(
                            FeatureItem(Icons.Default.Map, "Переключитесь на вкладку «Карта», чтобы увидеть динамику распространения воды"),
                            FeatureItem(Icons.Outlined.LocationOn, "Найдите свободные от затопления пути")
                        )
                    )
                    4 -> OnboardingPage(
                        icon = Icons.Outlined.WifiOff,
                        title = "Мы рядом, даже когда связи нет",
                        description = "В экстренной ситуации интернет может пропасть. Мы предусмотрели это: все важные инструкции и телефоны экстренных служб уже загружены в память вашего телефона.",
                        features = listOf(
                            FeatureItem(Icons.Outlined.Description, "Раздел «Инструкции» доступен полностью оффлайн"),
                            FeatureItem(Icons.Outlined.Description, "Все номера телефонов кликабельны — один тап, и вы уже звоните спасателям")
                        )
                    )
                }
            }

            // Логотип университета — отображается только на первом слайде
            AnimatedVisibility(
                visible = pagerState.currentPage == 0,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.utmn_logo),
                    contentDescription = "Логотип университета",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    contentScale = ContentScale.Fit
                )
            }

            // Индикаторы страниц
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (pagerState.currentPage == index) 24.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index) PrimaryBlue else Color.LightGray
                            )
                    )
                }
            }

            // Кнопки навигации
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка "Пропустить"
                if (pagerState.currentPage < 4) {
                    TextButton(
                        onClick = { onFinish() }
                    ) {
                        Text(
                            "Пропустить",
                            color = TextGray,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Кнопка "Далее" / "Начать"
                Button(
                    onClick = {
                        if (pagerState.currentPage < 4) {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onFinish()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .height(56.dp)
                        .then(
                            if (pagerState.currentPage == 4)
                                Modifier.fillMaxWidth()
                            else
                                Modifier.width(140.dp)
                        )
                ) {
                    Text(
                        text = if (pagerState.currentPage < 4) "Далее" else "Начать",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (pagerState.currentPage < 4) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Логотип приложения в красивом круглом контейнере
        Box(
            modifier = Modifier.padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Внешний круг с градиентом
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                LightBlue.copy(alpha = 0.3f),
                                PrimaryBlue.copy(alpha = 0.2f),
                                DarkBlue.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Белый круг для контраста
                Surface(
                    modifier = Modifier.size(200.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 12.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        // Замените R.drawable.app_logo на ваш ресурс
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "Логотип приложения",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        // Заголовок
        Text(
            text = "Добро пожаловать в Паводковый ИИ-консультант!",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = DarkBlue,
            lineHeight = 36.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Описание
        Text(
            text = "Это приложение поможет тебе получить всю необходимую информацию о паводковой обстановке в твоей местности!",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            color = TextGray,
            lineHeight = 28.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Декоративная волна
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            LightBlue,
                            PrimaryBlue,
                            DarkBlue
                        )
                    )
                )
        )
    }
}

data class FeatureItem(val icon: ImageVector, val text: String)

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    description: String,
    features: List<FeatureItem>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Иконка
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = LightBlue.copy(alpha = 0.2f)
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = PrimaryBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Заголовок
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = DarkBlue,
            lineHeight = 32.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Описание
        Text(
            text = description,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = TextGray,
            lineHeight = 24.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Список функций
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            features.forEach { feature ->
                FeatureRow(feature)
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: FeatureItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = LightBlue.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = PrimaryBlue
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = feature.text,
            fontSize = 15.sp,
            color = TextGray,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}