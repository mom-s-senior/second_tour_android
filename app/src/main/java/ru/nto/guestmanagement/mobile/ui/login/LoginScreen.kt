package ru.nto.guestmanagement.mobile.ui.login

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.io.IOException
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.TestIds

private val codeFormat = Regex("[A-Za-z0-9]{4}")

@Composable
fun LoginScreen(container: AppContainer, sessionExpired: Boolean, onLoggedIn: () -> Unit) {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val wasLight = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = true
        onDispose {
            if (wasLight != null) bars.isAppearanceLightStatusBars = wasLight
        }
    }

    val scope = rememberCoroutineScope()
    val api = remember(container) { RequestsApi(container.httpClient) }
    val openRequests by rememberUpdatedState(onLoggedIn)
    var code by rememberSaveable(sessionExpired) { mutableStateOf("") }
    var checkingSession by remember(container) { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(container, sessionExpired) {
        try {
            if (!sessionExpired && container.session.current() != null) openRequests()
        } catch (_: IOException) {
            error = "Не удалось прочитать сохранённый вход"
        } finally {
            checkingSession = false
        }
    }

    fun canSubmit() = !checkingSession && !submitting && codeFormat.matches(code)

    fun submitCode() {
        if (!canSubmit()) return
        val submittedCode = code
        submitting = true
        error = null
        scope.launch {
            try {
                when (api.auth(submittedCode)) {
                    is ApiResult.Success -> {
                        container.session.save(submittedCode)
                        openRequests()
                    }
                    ApiResult.Unauthorized -> error = "Неверный код сотрудника"
                    ApiResult.Network -> error = "Нет связи"
                    else -> error = "Не удалось войти"
                }
            } catch (_: IOException) {
                error = "Не удалось сохранить вход"
            } finally {
                submitting = false
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.White).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 76.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BuildingIcon()
            Spacer(Modifier.height(16.dp))
            Text(
                "Заявки на пропуск",
                color = Ink,
                fontFamily = FontFamily.SansSerif,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Text("Вход для сотрудников", color = SecondaryInk, fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(42.dp))

            if (sessionExpired) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .background(Lavender, LoginShape).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoIcon()
                    Text("Сессия завершена", Modifier.testTag(TestIds.AUTH_SESSION_EXPIRED), color = Ink, fontSize = 18.sp)
                }
                Spacer(Modifier.height(28.dp))
            }

            CodeField(code, !submitting, error != null) {
                code = it
                error = null
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp, bottom = 12.dp),
            ) {
                error?.let {
                    Text(it, Modifier.testTag(TestIds.AUTH_ERROR), color = ErrorRed, fontSize = 16.sp)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TestIds.AUTH_SUBMIT),
                    enabled = canSubmit(),
                    shape = LoginShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White,
                        disabledContainerColor = if (submitting) Accent else DisabledFill,
                        disabledContentColor = if (submitting) Color.White else DisabledInk,
                    ),
                    onClick = ::submitCode,
                ) {
                    if (!submitting) Text("Войти", fontSize = 18.sp)
                }
                if (submitting) {
                    // Индикатор вне кнопки, чтобы его тег оставался доступен тестам.
                    Row(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).testTag(TestIds.AUTH_LOADING),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.45f),
                            strokeWidth = 3.dp,
                        )
                        Text("Входим...", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

private val Ink = Color(0xFF13152F)
private val SecondaryInk = Color(0xFF656977)
private val Accent = Color(0xFF514BC1)
private val Lavender = Color(0xFFEBE9FA)
private val DisabledFill = Color(0xFFDAD9EC)
private val DisabledInk = Color(0xFF69698D)
private val ErrorRed = Color(0xFFBE1824)
private val LoginShape = RoundedCornerShape(12.dp)

@Composable
private fun CodeField(code: String, enabled: Boolean, isError: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = code,
        onValueChange = onChange,
        label = { Text("Код сотрудника") },
        enabled = enabled,
        singleLine = true,
        isError = isError,
        textStyle = TextStyle(fontSize = 18.sp, fontFamily = FontFamily.SansSerif),
        shape = LoginShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TestIds.AUTH_CODE),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            disabledTextColor = Ink,
            errorTextColor = Ink,
            focusedLabelColor = Accent,
            unfocusedLabelColor = SecondaryInk,
            disabledLabelColor = SecondaryInk,
            errorLabelColor = ErrorRed,
            cursorColor = Accent,
            errorCursorColor = ErrorRed,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color(0xFFA8ABB5),
            disabledBorderColor = Color(0xFFA8ABB5),
            errorBorderColor = ErrorRed,
        ),
    )
}

@Composable
private fun BuildingIcon() {
    Box(
        Modifier.size(80.dp).background(Lavender, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(54.dp)) {
            scale(size.width / 54f, size.height / 54f, Offset.Zero) {
                drawRoundRect(Accent, Offset(4f, 23f), Size(11f, 25f), CornerRadius(3f), style = Stroke(3f))
                drawRoundRect(Accent, Offset(39f, 23f), Size(11f, 25f), CornerRadius(3f), style = Stroke(3f))
                drawRoundRect(Accent, Offset(15f, 5f), Size(24f, 43f), CornerRadius(5f), style = Stroke(3f))
                for (y in listOf(13f, 21f, 29f)) {
                    for (x in listOf(21f, 29f)) {
                        drawRoundRect(Accent, Offset(x, y), Size(4f, 4f), CornerRadius(1f))
                    }
                }
                drawRoundRect(Accent, Offset(23f, 38f), Size(8f, 10f), CornerRadius(2f))
                drawLine(Accent, Offset(2f, 48f), Offset(52f, 48f), 3f)
            }
        }
    }
}

@Composable
private fun InfoIcon() {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        drawCircle(Accent, radius = 10.5f * unit, style = Stroke(2.4f * unit))
        drawCircle(Accent, radius = 1.25f * unit, center = Offset(12f * unit, 7.5f * unit))
        drawLine(Accent, Offset(12f * unit, 11f * unit), Offset(12f * unit, 17f * unit), 2f * unit)
    }
}
