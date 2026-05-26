package com.walarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walarm.app.data.DebugLog
import com.walarm.app.data.WatchedContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugLogsScreen(
    logs: List<DebugLog>,
    onClearLogs: () -> Unit,
    onAddContact: (WatchedContact) -> Unit
) {
    var contactToAdd by remember { mutableStateOf<WatchedContact?>(null) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss (MM/dd)", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notification Logs",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                
                if (logs.isNotEmpty()) {
                    Button(
                        onClick = onClearLogs,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📡", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Notifications Captured Yet",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "When you receive messages on WhatsApp, details will appear here to help you configure targets.",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(logs) { log ->
                        LogCard(
                            log = log,
                            formattedTime = dateFormat.format(Date(log.timestamp)),
                            onAddToWatchlist = {
                                contactToAdd = WatchedContact(
                                    name = log.parsedSender ?: "Unknown",
                                    isGroup = log.isGroupChat,
                                    ringtonePath = null,
                                    useAlarmVolume = true
                                )
                            }
                        )
                    }
                }
            }
        }

        contactToAdd?.let { contact ->
            ContactDialog(
                contact = contact,
                onDismiss = { contactToAdd = null },
                onSave = {
                    onAddContact(it)
                    contactToAdd = null
                }
            )
        }
    }
}

@Composable
fun LogCard(
    log: DebugLog,
    formattedTime: String,
    onAddToWatchlist: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (log.matched) Color(0xFF34C759).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Status and Time Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (log.matched) Color(0xFF34C759).copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = if (log.matched) " ALARM TRIGGERED " else " IGNORED ",
                            color = if (log.matched) Color(0xFF34C759) else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (log.isGroupChat) {
                        BadgeLabel("Group", Color(0xFF007AFF))
                    }
                }
                
                Text(
                    text = formattedTime,
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sender and Message Details
            Text(
                text = "Sender: ${log.parsedSender ?: "Null"}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Message: ${log.parsedMessage ?: "Null"}",
                color = Color.LightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp)
            )

            // Extra Debug Metadata
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Raw Title: ${log.title ?: "null"}", color = Color.Gray, fontSize = 11.sp)
                Text("Raw Text: ${log.text ?: "null"}", color = Color.Gray, fontSize = 11.sp)
                if (!log.subText.isNullOrEmpty()) {
                    Text("Raw SubText: ${log.subText}", color = Color.Gray, fontSize = 11.sp)
                }
                if (!log.conversationTitle.isNullOrEmpty()) {
                    Text("Raw Conv Title: ${log.conversationTitle}", color = Color.Gray, fontSize = 11.sp)
                }
            }

            // Quick Add action
            if (!log.matched) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onAddToWatchlist,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE).copy(alpha = 0.2f)),
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color(0xFF985EFF),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add to Watchlist", color = Color(0xFF985EFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
