package nz.fishingnz.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel

@Composable fun AccountScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showSignOutEverywhere by remember { mutableStateOf(false) }
    var showSyncOff by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var countryCode by rememberSaveable { mutableStateOf("NZ") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    val validEmail = remember(email) { android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() }
    val passwordsMatch = confirmPassword.isNotEmpty() && password == confirmPassword
    val validPassword = password.isNotBlank() && password.length <= 128 && (!createAccount || (password.length >= 8 && passwordsMatch))
    LaunchedEffect(Unit) { vm.refreshSignInProviders(); vm.refreshAccount() }
    val socialButtons: @Composable () -> Unit = {
        listOf("google" to "Google", "apple" to "Apple").forEach { (provider, label) ->
            val connected = s.account != null && provider in s.signInProviders.connected
            val available = if (provider == "google") s.signInProviders.google else s.signInProviders.apple
            OutlinedButton(enabled = !s.accountBusy && available && !connected, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                onClick = { vm.startSocialSignIn(provider) { url -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) {
                if (connected) { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(if (connected) "$label connected" else if (s.account != null) "Connect $label" else "Continue with $label")
            }
        }
        if (!s.signInProviders.google && !s.signInProviders.apple) Text("Google and Apple sign-in will be available soon.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    LaunchedEffect(s.account?.user) {
        s.account?.let { displayName = it.user.displayName; countryCode = it.user.countryCode; email = it.user.email }
    }
    LaunchedEffect(s.verificationPending, s.account) {
        if (s.verificationPending || s.account != null) {
            createAccount = false
            password = ""
            confirmPassword = ""
        }
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF082C3C), Color(0xFF164B61))), RoundedCornerShape(28.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    val initials = s.account?.user?.displayName?.trim()?.split(Regex("\\s+"))?.take(2)?.mapNotNull { it.firstOrNull()?.uppercase() }?.joinToString("")
                    if (!initials.isNullOrBlank()) Text(initials, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    else Icon(Icons.Default.PersonOutline, null, Modifier.size(30.dp), tint = Color.White)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("FISHDAYS · NZ", color = Color(0xFFBFE6DF), style = MaterialTheme.typography.labelMedium)
                    // The top bar already says Account, so the header names the person, or stays untitled when signed out.
                    s.account?.user?.let { user -> user.displayName.takeIf { it.isNotBlank() } ?: user.email }?.let { title ->
                        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            if (s.account == null) Text("Sign in to identify fish and manage your profile.",
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
        s.accountNotice?.let { Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = Navy) } }
        s.accountError?.let { Card(colors = CardDefaults.cardColors(Color(0xFFFFEBE7)), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (s.account == null && it.startsWith("Could not check your account")) TextButton(onClick = vm::refreshAccount) { Text("Retry account check") }
            }
        } }
        if (s.account == null && s.accountLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking your account…", color = Navy)
            }
        } else if (s.account == null && s.hasStoredSession) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Session saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Your sign-in is saved, but we could not check account access. Check your connection and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::refreshAccount) { Text("Retry account check") }
                }
            }
        } else if (s.account == null) {
            if (s.verificationPending) Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Check your inbox", color = Navy, fontWeight = FontWeight.Bold); Text("Open the confirmation email, then sign in. The link expires after 24 hours.", color = Navy); OutlinedButton(enabled = !s.accountBusy && validEmail, onClick = { vm.resendVerification(email) }) { Text("Resend confirmation email") } } }
            AccountPanel {
                socialButtons()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("or use email", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    HorizontalDivider(Modifier.weight(1f))
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "Sign in", true to "Create account").forEachIndexed { index, (create, label) ->
                        SegmentedButton(selected = createAccount == create,
                            onClick = { createAccount = create; password = ""; confirmPassword = ""; passwordVisible = false },
                            shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(label) })
                    }
                }
                if (createAccount) OutlinedTextField(displayName, { displayName = it }, label = { Text("Name (optional)") }, leadingIcon = { Icon(Icons.Default.PersonOutline, null) }, shape = RoundedCornerShape(14.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email address") }, leadingIcon = { Icon(Icons.Default.MailOutline, null) }, shape = RoundedCornerShape(14.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, supportingText = { Text(if (createAccount) "At least 8 characters" else "Enter your password") }, shape = RoundedCornerShape(14.dp), leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (passwordVisible) "Hide password" else "Show password") } },
                    singleLine = true, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (createAccount) OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Confirm password") },
                    isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                    supportingText = { Text(if (confirmPassword.isEmpty()) "Enter your password again" else if (passwordsMatch) "Passwords match" else "Passwords don’t match") },
                    shape = RoundedCornerShape(14.dp), leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (passwordVisible) "Hide password" else "Show password") } },
                    singleLine = true, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Button(enabled = !s.accountBusy && validEmail && validPassword, onClick = { vm.signIn(email.trim(), password, displayName, createAccount) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    if (s.accountBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                    Text(if (s.accountBusy) "Please wait…" else if (createAccount) "Create account" else "Sign in")
                }
                if (!createAccount) TextButton(onClick = {
                    nz.fishingnz.app.data.Analytics.track("password_reset_opened")
                    uriHandler.openUri(nz.fishingnz.app.data.PrivacyLinks.forgotPassword)
                }) { Text("Forgot password?") }
                if (!createAccount && !s.verificationPending) TextButton(enabled = !s.accountBusy && validEmail, onClick = { vm.resendVerification(email.trim()) }) { Text("Resend confirmation email") }
            }
        } else {
            AccountPanel {
                AccountSectionHeading("Profile", s.account.user.email, Icons.Default.PersonOutline)
                OutlinedTextField(displayName, { displayName = it.take(80) }, label = { Text("Display name") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(countryCode, { countryCode = it.take(2).uppercase() }, label = { Text("Country code") }, supportingText = { Text("Two-letter code, e.g. NZ") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Button(enabled = !s.accountBusy && countryCode.matches(Regex("[A-Z]{2}")), onClick = { vm.saveAccountProfile(displayName, countryCode) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) { Text(if (s.accountBusy) "Please wait…" else "Save changes") }
            }
            AccountPanel {
                AccountSectionHeading("Sync trips and catch log", "Use them on your other signed-in devices", Icons.Default.Sync)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync on this device", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Navy)
                    Switch(checked = s.syncEnabled, onCheckedChange = { if (it) vm.setSyncEnabled(true) else showSyncOff = true })
                }
                Text("Saved spots, the active trip and your catch log, including each catch's position and notes, are kept with your account. Photos stay on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.syncEnabled) {
                    val status = when {
                        s.syncing -> "Syncing…"
                        s.syncError != null -> s.syncError
                        s.lastSyncedAt != null -> "Last synced ${nz.fishingnz.app.data.OfflineCache.savedLabel(s.lastSyncedAt)}"
                        else -> "Not synced yet"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (s.syncError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !s.syncing, onClick = vm::syncNow, contentPadding = PaddingValues(0.dp)) { Text("Sync now") }
                }
            }
            AccountPanel {
                AccountSectionHeading("Sign-in options", "Google or Apple", Icons.Default.Lock)
                socialButtons()
            }
            AccountPanel {
                AccountSectionHeading("Password", if (s.account.user.hasPassword) null else "Add one to also sign in with your email.", Icons.Default.Lock)
                if (s.account.user.hasPassword) {
                    OutlinedButton(enabled = !s.accountBusy, onClick = { showChangePassword = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) { Text("Change password") }
                } else {
                    OutlinedButton(enabled = !s.accountBusy, onClick = {
                        nz.fishingnz.app.data.Analytics.track("password_reset_opened")
                        uriHandler.openUri(nz.fishingnz.app.data.PrivacyLinks.forgotPassword)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) { Text("Add a password") }
                }
                TextButton(enabled = !s.accountBusy, onClick = { showSignOutEverywhere = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Sign out of all devices") }
            }
            OutlinedButton(enabled = !s.accountBusy, onClick = vm::signOut, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
            TextButton(enabled = !s.accountBusy, onClick = { showDeleteConfirmation = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { uriHandler.openUri(nz.fishingnz.app.data.PrivacyLinks.policy) }) { Text("Privacy policy") }
        }
    }
    if (showChangePassword) {
        var current by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        val problem = when {
            next.isNotEmpty() && (next.length < 8 || next.length > 128) -> "Use 8 to 128 characters for the new password."
            next.isNotEmpty() && next == current -> "Choose a password you are not already using."
            again.isNotEmpty() && again != next -> "The new passwords don’t match."
            else -> null
        }
        val valid = current.isNotEmpty() && next.length in 8..128 && next != current && again == next
        val masking = if (visible) VisualTransformation.None else PasswordVisualTransformation()
        AlertDialog(onDismissRequest = { showChangePassword = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You stay signed in on this phone. Every other device is signed out.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = masking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide passwords" else "Show passwords") } })
                    OutlinedTextField(next, { next = it }, label = { Text("New password") }, singleLine = true, visualTransformation = masking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(again, { again = it }, label = { Text("Repeat new password") }, singleLine = true, visualTransformation = masking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = valid && !s.accountBusy, onClick = { showChangePassword = false; vm.changePassword(current, next) }) { Text("Change password") } },
            dismissButton = { TextButton(onClick = { showChangePassword = false }) { Text("Cancel") } })
    }
    if (showSyncOff) AlertDialog(onDismissRequest = { showSyncOff = false },
        title = { Text("Turn off sync?") },
        text = { Text("This phone keeps its trips and catch log. You can also delete the copy kept with your account; your other devices keep theirs.") },
        confirmButton = { TextButton(onClick = { showSyncOff = false; vm.setSyncEnabled(false, deleteSyncedCopy = true) }) { Text("Turn off and delete copy", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showSyncOff = false; vm.setSyncEnabled(false) }) { Text("Turn off, keep copy") } })
    if (showSignOutEverywhere) AlertDialog(onDismissRequest = { showSignOutEverywhere = false },
        title = { Text("Sign out of all devices?") },
        text = { Text("This ends every session for your account, including this phone. You will need to sign in again everywhere.") },
        confirmButton = { TextButton(enabled = !s.accountBusy, onClick = { showSignOutEverywhere = false; vm.signOutEverywhere() }) { Text("Sign out everywhere") } },
        dismissButton = { TextButton(onClick = { showSignOutEverywhere = false }) { Text("Cancel") } })
    if (showDeleteConfirmation) AlertDialog(onDismissRequest = { showDeleteConfirmation = false },
        title = { Text("Permanently delete your account?") },
        text = { Text("This removes your profile, sign-in connections, sessions, feedback, synced trips and catch log, and account-linked usage records from our live database. It also clears this device’s selected fish photo and trip shortlist and resets optional analytics. It cannot be undone. Recovery copies can remain for up to 30 days. Your Google or Apple account and gallery photos remain available.") },
        confirmButton = { TextButton(enabled = !s.accountBusy, onClick = { showDeleteConfirmation = false; vm.deleteAccount() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") } })
}


@Composable private fun AccountPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun AccountSectionHeading(title: String, subtitle: String?, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).background(Seafoam, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Navy, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
