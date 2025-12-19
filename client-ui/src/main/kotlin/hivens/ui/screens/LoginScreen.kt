package hivens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.di
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: (SessionData) -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier.width(420.dp).wrapContentHeight(),
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

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Логин") },
                    modifier = Modifier.fillMaxWidth(),
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

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

                CelestiaButton(
                    text = if (isLoading) "Вход..." else "Войти",
                    enabled = !isLoading && login.isNotEmpty() && password.isNotEmpty(),
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val lastServer = di.profileManager.lastServerId ?: "Industrial" // Почему не Create?
                                val session = di.authService.login(login, password, lastServer)
                                if (rememberMe) {
                                    di.credentialsManager.save(login, password)
                                }
                                onLoginSuccess(session)
                            } catch (e: Exception) {
                                errorMessage = "Ошибка входа: ${e.message}"
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                )
            }
        }
    }
}
