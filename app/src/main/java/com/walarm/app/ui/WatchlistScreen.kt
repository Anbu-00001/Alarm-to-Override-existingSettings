package com.walarm.app.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.data.WatchedContact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    contacts: List<WatchedContact>,
    onAddContact: (WatchedContact) -> Unit,
    onUpdateContact: (WatchedContact) -> Unit,
    onDeleteContact: (WatchedContact) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<WatchedContact?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testingContactId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📋", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Your Watchlist is Empty",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add contacts or groups that you want to trigger custom alarms.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        isTesting = testingContactId == contact.id,
                        onEdit = { editingContact = contact },
                        onDelete = { onDeleteContact(contact) },
                        onTest = {
                            if (testingContactId == contact.id) {
                                AlarmPlayer.stop()
                                testingContactId = null
                            } else {
                                AlarmPlayer.stop()
                                testingContactId = contact.id
                                AlarmPlayer.play(context, contact)
                                scope.launch {
                                    delay(4000) // Auto stop test after 4 seconds
                                    if (testingContactId == contact.id) {
                                        AlarmPlayer.stop()
                                        testingContactId = null
                                    }
                                }
                            }
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = Color(0xFF6200EE),
            contentColor = Color.White
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add Contact")
        }

        if (showAddDialog) {
            ContactDialog(
                onDismiss = { showAddDialog = false },
                onSave = {
                    onAddContact(it)
                    showAddDialog = false
                }
            )
        }

        editingContact?.let { contact ->
            ContactDialog(
                contact = contact,
                onDismiss = { editingContact = null },
                onSave = {
                    onUpdateContact(it)
                    editingContact = null
                }
            )
        }
    }
}

@Composable
fun ContactRow(
    contact: WatchedContact,
    isTesting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (contact.isGroup) "👥" else "👤",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = contact.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (contact.repeatUntilDismissed) {
                        BadgeLabel("VIP Loop", Color(0xFFFF3B30))
                    }
                    if (contact.isScheduleEnabled) {
                        BadgeLabel(
                            String.format("%02d:%02d-%02d:%02d", contact.startHour, contact.startMinute, contact.endHour, contact.endMinute),
                            Color(0xFF007AFF)
                        )
                    }
                    if (contact.isKeywordFilterEnabled) {
                        BadgeLabel("Keywords", Color(0xFFFF9500))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Test button
                Button(
                    onClick = onTest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTesting) Color(0xFFFF3B30) else Color(0xFF34C759).copy(alpha = 0.2f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = if (isTesting) "Stop" else "Test 🔊",
                        color = if (isTesting) Color.White else Color(0xFF34C759),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Contact",
                        tint = Color.LightGray
                    )
                }
            }
        }
    }
}

@Composable
fun BadgeLabel(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDialog(
    contact: WatchedContact? = null,
    onDismiss: () -> Unit,
    onSave: (WatchedContact) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var isGroup by remember { mutableStateOf(contact?.isGroup ?: false) }
    var ringtonePath by remember { mutableStateOf(contact?.ringtonePath ?: "") }
    var ringtoneName by remember { mutableStateOf("Default System Tone") }
    var useAlarmVolume by remember { mutableStateOf(contact?.useAlarmVolume ?: true) }
    var repeatUntilDismissed by remember { mutableStateOf(contact?.repeatUntilDismissed ?: false) }
    var escalatingVolume by remember { mutableStateOf(contact?.escalatingVolume ?: false) }
    var cooldownSeconds by remember { mutableStateOf(contact?.cooldownSeconds?.toString() ?: "30") }
    
    // Schedule states
    var isScheduleEnabled by remember { mutableStateOf(contact?.isScheduleEnabled ?: false) }
    var startHour by remember { mutableStateOf(contact?.startHour ?: 9) }
    var startMinute by remember { mutableStateOf(contact?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(contact?.endHour ?: 23) }
    var endMinute by remember { mutableStateOf(contact?.endMinute ?: 0) }
    var vibeOnlyOutsideSchedule by remember { mutableStateOf(contact?.vibeOnlyOutsideSchedule ?: true) }

    // Keyword states
    var isKeywordFilterEnabled by remember { mutableStateOf(contact?.isKeywordFilterEnabled ?: false) }
    var keywords by remember { mutableStateOf(contact?.keywords ?: "urgent,help,emergency,call me") }

    // Ringtone picker launcher
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                ringtonePath = uri.toString()
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtoneName = ringtone.getTitle(context)
            } else {
                ringtonePath = ""
                ringtoneName = "Silent"
            }
        }
    }

    // Load initial ringtone name if editing
    LaunchedEffect(ringtonePath) {
        if (ringtonePath.isNotEmpty()) {
            try {
                val ringtone = RingtoneManager.getRingtone(context, Uri.parse(ringtonePath))
                ringtoneName = ringtone.getTitle(context)
            } catch (e: Exception) {
                ringtoneName = "Custom Ringtone"
            }
        } else {
            ringtoneName = "Default System Tone"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(2.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1A33))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (contact == null) "Add Watch Target" else "Edit Watch Target",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // Name Input
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Contact or Group Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF985EFF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    ),
                    singleLine = true
                )

                // Individual vs Group Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Is this a Group Chat?", color = Color.White)
                    Switch(
                        checked = isGroup,
                        onCheckedChange = { isGroup = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF985EFF))
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // Ringtone Selection Card
                Column {
                    Text("Sound Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                    if (ringtonePath.isNotEmpty()) {
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(ringtonePath))
                                    }
                                }
                                ringtonePickerLauncher.launch(intent)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.05f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Ringtone Sound", color = Color.Gray, fontSize = 12.sp)
                            Text(ringtoneName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Volume and Alert Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Use Alarm Stream", color = Color.White)
                        Text("Play louder & bypass DND/Mute", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = useAlarmVolume, onCheckedChange = { useAlarmVolume = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("VIP Loop Mode", color = Color.White)
                        Text("Repeat sound until dismissed manually", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = repeatUntilDismissed, onCheckedChange = { repeatUntilDismissed = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Escalating Volume", color = Color.White)
                        Text("Gradually increase volume from low", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = escalatingVolume, onCheckedChange = { escalatingVolume = it })
                }

                // Cooldown input
                OutlinedTextField(
                    value = cooldownSeconds,
                    onValueChange = { cooldownSeconds = it.filter { char -> char.isDigit() } },
                    label = { Text("Cooldown Duration (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF985EFF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    )
                )

                Divider(color = Color.White.copy(alpha = 0.1f))

                // Time Schedule Options
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Time-Aware Schedule", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Restrict alarm to certain hours", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(checked = isScheduleEnabled, onCheckedChange = { isScheduleEnabled = it })
                    }

                    if (isScheduleEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Start Time clicker
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                startHour = hour
                                                startMinute = minute
                                            },
                                            startHour,
                                            startMinute,
                                            true
                                        ).show()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Start Time", color = Color.Gray, fontSize = 11.sp)
                                    Text(String.format("%02d:%02d", startHour, startMinute), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // End Time clicker
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                endHour = hour
                                                endMinute = minute
                                            },
                                            endHour,
                                            endMinute,
                                            true
                                        ).show()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("End Time", color = Color.Gray, fontSize = 11.sp)
                                    Text(String.format("%02d:%02d", endHour, endMinute), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Outside action switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Vibrate Only Outside Schedule", color = Color.White, fontSize = 14.sp)
                                Text("Vibrate gently instead of loud alarm at night", color = Color.Gray, fontSize = 11.sp)
                            }
                            Switch(checked = vibeOnlyOutsideSchedule, onCheckedChange = { vibeOnlyOutsideSchedule = it })
                        }
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // Keyword Filter Options
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Keyword Override Filter", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Trigger ONLY if message contains keywords", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(checked = isKeywordFilterEnabled, onCheckedChange = { isKeywordFilterEnabled = it })
                    }

                    if (isKeywordFilterEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keywords,
                            onValueChange = { keywords = it },
                            label = { Text("Keywords (comma separated)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF985EFF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Save and Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.LightGray)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                val cooldownInt = cooldownSeconds.toIntOrNull() ?: 30
                                val updated = WatchedContact(
                                    id = contact?.id ?: 0,
                                    name = name.trim(),
                                    isGroup = isGroup,
                                    ringtonePath = ringtonePath.ifEmpty { null },
                                    useAlarmVolume = useAlarmVolume,
                                    repeatUntilDismissed = repeatUntilDismissed,
                                    escalatingVolume = escalatingVolume,
                                    cooldownSeconds = cooldownInt,
                                    lastTriggeredTime = contact?.lastTriggeredTime ?: 0L,
                                    isScheduleEnabled = isScheduleEnabled,
                                    startHour = startHour,
                                    startMinute = startMinute,
                                    endHour = endHour,
                                    endMinute = endMinute,
                                    vibeOnlyOutsideSchedule = vibeOnlyOutsideSchedule,
                                    isKeywordFilterEnabled = isKeywordFilterEnabled,
                                    keywords = keywords.trim()
                                )
                                onSave(updated)
                            }
                        },
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}
