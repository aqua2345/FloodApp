package com.example.floodapptest

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException

data class Instruction(val title: String, val fileName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val instructions = listOf(

        Instruction("Действия при паводке", "flood_actions.txt"),

        Instruction("Документы при эвакуации", "documents.txt"),

        Instruction("Как обезопасить себя?", "emergency_bag.txt"),

        Instruction("Рекомендации медиков", "first_aid.txt"),

        Instruction("Подготовка к эвакуации", "house_protection.txt"),

        Instruction("Помощь пострадавшему в воде", "animals.txt"),

        Instruction("Что помогут узнать в 122", "in_water.txt"),

        Instruction("Телефоны горячих линий", "after_flood.txt")

    )

    var selectedInstruction by remember { mutableStateOf<Instruction?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Заголовок экрана
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp).clickable { onBack() },
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.padding(8.dp))
                }
                Text(
                    "Инструкции",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(40.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(instructions) { item ->
                    InstructionCard(item) {
                        selectedInstruction = item
                        showSheet = true
                    }
                }
            }
        }

        if (showSheet && selectedInstruction != null) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                val fileContent = remember(selectedInstruction) {
                    try {
                        context.assets.open(selectedInstruction!!.fileName)
                            .bufferedReader()
                            .use { it.readText() }
                    } catch (e: IOException) {
                        "Ошибка загрузки файла"
                    }
                }

                InstructionDetailContent(selectedInstruction!!.title, fileContent)
            }
        }
    }
}

@Composable
fun InstructionCard(instruction: Instruction, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF8F9FA),
        border = BorderStroke(1.dp, Color(0xFFE9ECEF))
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = Color(0xFF495057))
            Spacer(Modifier.width(16.dp))
            Text(instruction.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun InstructionDetailContent(title: String, content: String) {
    val context = LocalContext.current
    // Парсим текст на наличие телефонов
    val annotatedText = parseTextWithPhones(content)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Используем ClickableText вместо обычного Text
        ClickableText(
            text = annotatedText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = Color(0xFF495057)
            ),
            onClick = { offset ->
                annotatedText.getStringAnnotations("PHONE", offset, offset).firstOrNull()?.let {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${it.item}")
                    }
                    context.startActivity(intent)
                }
            }
        )
    }
}

// Функция для поиска телефонов в тексте инструкций
fun parseTextWithPhones(text: String): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        // Телефоны: +7/8 стандарт | экстренные 1XX | местный XX-XX-XX | нестандартный X(XXXXX)X-XX-XX
        val phoneRegex = ("(?:(?:\\+7|8)[\\s\\-]?\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2})" +
                "|(?:\\b1\\d{2}\\b)" +
                "|(?:\\b\\d{2}[\\-]\\d{2}[\\-]\\d{2}\\b)" +
                "|(?:\\b\\d\\(\\d{5}\\)\\d[\\-]\\d{2}[\\-]\\d{2}\\b)").toRegex()

        phoneRegex.findAll(text).forEach { match ->
            // Применяем стиль к найденному номеру
            addStyle(
                style = SpanStyle(
                    color = Color(0xFF007AFF),
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
            // Добавляем аннотацию для клика
            addStringAnnotation(
                tag = "PHONE",
                annotation = match.value.replace(Regex("[^\\d+]"), ""),
                start = match.range.first,
                end = match.range.last + 1
            )
        }
    }
}