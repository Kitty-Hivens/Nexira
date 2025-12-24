package hivens.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IAuthService
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Экран авторизации пользователя.
 *
 * <p>Обрабатывает ввод логина/пароля, анимацию ошибок (тряска) и
 * сохранение учетных данных.</p>
 *
 * @param onLoginSuccess Callback, вызываемый при успешной авторизации.
 */
@Composable
fun LoginScreen(onLoginSuccess: (SessionData) -> Unit) {
    // Внедрение зависимостей
    val authService: IAuthService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val credentialsManager: CredentialsManager = koinInject()

    // Состояние полей ввода
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    // Состояние UI
    var isLoading by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Анимация появления и "тряски" при ошибке
    val shakeOffset = remember { Animatable(0f) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { isVisible = true }

    fun triggerShake() {
        scope.launch {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f at 0
                    (-10f) at 50
                    10f at 100
                    (-10f) at 150
                    10f at 200
                    (-5f) at 250
                    5f at 300
                    0f at 400
                }
            )
        }
    }

    val buttonColor by animateColorAsState(
        if (isSuccess) CelestiaTheme.colors.success else CelestiaTheme.colors.primary
    )

    fun doLogin() {
        if (login.isEmpty() || password.isEmpty()) {
            errorMessage = "Введите логин и пароль"
            triggerShake()
            return
        }

        focusManager.clearFocus()
        isLoading = true
        errorMessage = null

        scope.launch {
            try {
                // Выполняем авторизацию в IO потоке
                val session = withContext(Dispatchers.IO) {
                    val lastServer = profileManager.lastServerId ?: "Industrial"
                    val s = authService.login(login, password, lastServer)

                    if (rememberMe) {
                        credentialsManager.save(login, password)
                    }
                    s
                }

                // Успех
                isLoading = false
                isSuccess = true
                delay(1500)
                onLoginSuccess(session)

            } catch (e: Exception) {
                isLoading = false
                // Очищаем сообщение об ошибке от технического мусора
                errorMessage = e.message?.replace("java.lang.Exception: ", "")
                    ?.substringAfter("API: ")
                    ?: "Ошибка входа"
                triggerShake()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = scaleIn(initialScale = 0.9f) + fadeIn(tween(500)),
            exit = fadeOut()
        ) {
            GlassCard(
                modifier = Modifier
                    .width(420.dp)
                    .wrapContentHeight()
                    .offset(x = shakeOffset.value.dp),
                shape = RoundedCornerShape(24.dp),
                backgroundColor = Color.Black.copy(alpha = 0.4f)
            ) {
                Column(
                    modifier = Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Aura Client",
                        style = MaterialTheme.typography.h4,
                        fontWeight = FontWeight.Bold,
                        color = CelestiaTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    // Блок ошибки
                    AnimatedVisibility(
                        visible = errorMessage != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .background(CelestiaTheme.colors.error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = CelestiaTheme.colors.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = CelestiaTheme.colors.error,
                                style = MaterialTheme.typography.body2
                            )
                        }
                    }

                    // Поле Логина
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it; errorMessage = null },
                        label = { Text("Логин") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = CelestiaTheme.colors.textPrimary,
                            focusedBorderColor = CelestiaTheme.colors.primary,
                            unfocusedBorderColor = CelestiaTheme.colors.border.copy(alpha = 0.5f),
                            focusedLabelColor = CelestiaTheme.colors.primary,
                            unfocusedLabelColor = CelestiaTheme.colors.textSecondary,
                            cursorColor = CelestiaTheme.colors.primary,
                            backgroundColor = Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Поле Пароля
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("Пароль") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { doLogin() }),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = CelestiaTheme.colors.textPrimary,
                            focusedBorderColor = CelestiaTheme.colors.primary,
                            unfocusedBorderColor = CelestiaTheme.colors.border.copy(alpha = 0.5f),
                            focusedLabelColor = CelestiaTheme.colors.primary,
                            unfocusedLabelColor = CelestiaTheme.colors.textSecondary,
                            cursorColor = CelestiaTheme.colors.primary,
                            backgroundColor = Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Чекбокс "Запомнить"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { rememberMe = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = CelestiaTheme.colors.primary,
                                uncheckedColor = CelestiaTheme.colors.border,
                                checkmarkColor = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запомнить пароль", color = CelestiaTheme.colors.textPrimary)
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    // Кнопка Входа
                    Button(
                        onClick = { doLogin() },
                        enabled = !isLoading && !isSuccess,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = buttonColor,
                            disabledBackgroundColor = buttonColor.copy(alpha = 0.7f)
                        )
                    ) {
                        Crossfade(targetState = when {
                            isSuccess -> "SUCCESS"
                            isLoading -> "LOADING"
                            else -> "IDLE"
                        }) { state ->
                            when (state) {
                                "LOADING" -> CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                "SUCCESS" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("УСПЕШНО", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                else -> Text("ВОЙТИ", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
