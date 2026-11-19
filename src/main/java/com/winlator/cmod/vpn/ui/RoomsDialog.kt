package com.winlator.cmod.vpn.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.vpn.R
import com.winlator.cmod.vpn.core.ActiveRoom
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.Nickname
import com.winlator.cmod.vpn.core.RoomInfo
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.RoomSession
import com.winlator.cmod.vpn.core.TextFilter
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.VpnNative
import com.winlator.cmod.vpn.directory.DirectoryState
import com.winlator.cmod.vpn.directory.JoinResult
import com.winlator.cmod.vpn.directory.RoomDirectoryProvider
import com.winlator.cmod.vpn.service.RoomVpnLauncher
import com.winlator.cmod.vpn.transport.RoomEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object RoomsDialog {
    fun show(activity: ComponentActivity) {
        val dialog = ComponentDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val composeView = ComposeView(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setContent {
                VpnTheme { RoomsScreen(activity, onClose = dialog::dismiss) }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.76f }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }
}

@Composable
private fun RoomsScreen(activity: ComponentActivity, onClose: () -> Unit) {
    val directory = RoomDirectoryProvider.instance
    val active = RoomSession.current
    var showCreate by remember { mutableStateOf(false) }
    // The room being joined while the nickname is asked, and why the host refused the last try.
    var joinTarget by remember { mutableStateOf<RoomInfo?>(null) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var lastCode by remember { mutableStateOf("") }
    // True when the player already typed the join code in the "Join with code" box, so the join
    // dialog does not ask for it again.
    var codeFromJoinCode by remember { mutableStateOf(false) }
    var joinCodeText by remember { mutableStateOf("") }
    var joinCodeError by remember { mutableStateOf<String?>(null) }
    // The join code being looked up, and the rooms that matched it when more than one did.
    var lookup by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showPrivate by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var preparingHost by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf<Map<String, RoomEngine.PeerState>>(emptyMap()) }

    DisposableEffect(directory) {
        directory.start()
        onDispose { directory.stop() }
    }
    // The tunnel state lives in the native engine, so it is polled while a room is active.
    LaunchedEffect(active != null) {
        peers = emptyMap()
        if (active == null) return@LaunchedEffect
        while (true) {
            peers = withContext(Dispatchers.IO) { RoomEngine.status() }.associateBy { it.address }
            delay(2_000)
        }
    }
    val engineStatus = remember {
        if (VpnNative.isAvailable) {
            val selfTest = VpnNative.nativeSelfTest()
            val version = "Palazos VPN engine ${VpnNative.nativeVersion()}"
            if (selfTest.startsWith("OK")) version else "$version ($selfTest)"
        } else "Engine not available in this build"
    }

    fun startTunnel(room: RoomInfo, isHost: Boolean, address: String, code: String? = null) {
        RoomVpnLauncher.start(activity, address, isHost) { granted ->
            if (granted) {
                RoomSession.activate(ActiveRoom(room, isHost, address, code), activity)
                message = null
            } else {
                if (isHost) directory.removeRoom(room.id) else directory.leaveRoom(room.id)
                message = "VPN permission was denied"
            }
            joining = false
        }
    }

    fun host(room: RoomInfo, code: String?) = startTunnel(room, true, VpnConfig.HOST_ADDRESS, code)

    // The address is assigned by the host, so the tunnel only comes up once it answered.
    fun join(room: RoomInfo, nickname: String, code: String?) {
        joining = true
        message = null
        directory.joinRoom(room, nickname, code) { result ->
            when (result) {
                is JoinResult.Joined -> startTunnel(room, false, result.address)
                is JoinResult.Rejected -> {
                    if (result.nicknameProblem || result.codeProblem) {
                        // Ask again, keeping the reason on screen.
                        nicknameError = result.reason.takeIf { result.nicknameProblem }
                        codeError = result.reason.takeIf { result.codeProblem }
                        if (result.codeProblem) codeFromJoinCode = false
                        joinTarget = room
                    } else {
                        message = result.reason
                    }
                    joining = false
                }
            }
        }
    }

    fun openFromCode(room: RoomInfo, code: String) {
        if (room.isFull) {
            message = "The room is full"
            return
        }
        nicknameError = null
        codeError = null
        lastCode = code
        codeFromJoinCode = true
        joinTarget = room
    }

    // The room list fills in from the broker, so a room named by a join code may take a moment to show up.
    LaunchedEffect(lookup) {
        val code = lookup ?: return@LaunchedEffect
        var found = emptyList<RoomInfo>()
        var waited = 0L
        while (found.isEmpty() && waited < LOOKUP_TIMEOUT_MS) {
            found = directory.rooms.filter { it.joinTag != null && JoinCode.matches(it, code) }
            if (found.isEmpty()) {
                delay(LOOKUP_STEP_MS)
                waited += LOOKUP_STEP_MS
            }
        }
        when {
            found.isEmpty() -> message = if (directory.state == DirectoryState.ONLINE) {
                "Room not found. The host may have closed it"
            } else {
                "The room directory is not reachable"
            }
            // Different rooms can rarely share a tag; the player picks instead of guessing.
            found.size > 1 -> {
                lastCode = code
                choices = found
            }
            else -> openFromCode(found.first(), code)
        }
        lookup = null
    }

    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choices = emptyList() },
            title = { Text("Several rooms match this code") },
            text = {
                Column {
                    choices.forEach { room ->
                        TextButton(onClick = {
                            choices = emptyList()
                            openFromCode(room, lastCode)
                        }) { Text("${room.name} - ${room.hostName}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choices = emptyList() }) { Text("Cancel") } }
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    // The whole page scrolls; a list nested in a fixed column got almost no height.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Rooms",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                }

                Text(
                    "Players connect through an encrypted tunnel to the host, which " +
                        "relays traffic between them. Hosting needs a network that allows direct " +
                        "connections (most home Wi-Fi does; mobile data often does not).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Directory: ${directory.state.label()}  |  $engineStatus\n" +
                        "Dev: Palazos\n" +
                        "Big Thanks: Ziads, Psycho",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                (message ?: RoomSession.notice)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (preparingHost) Text("Preparing the room...", style = MaterialTheme.typography.bodyMedium)
                if (joining) Text("Joining the room...", style = MaterialTheme.typography.bodyMedium)
                if (lookup != null) Text("Looking for the room...", style = MaterialTheme.typography.bodyMedium)

                if (active != null) {
                    ActiveRoomCard(
                        active, RoomSession.members, peers,
                        onKick = { directory.kickMember(it.address) },
                        onLeave = { RoomVpnLauncher.stop(activity) }
                    )
                } else {
                    Button(
                        onClick = { showCreate = true },
                        enabled = !joining,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create room")
                    }
                    JoinCodeRow(
                        text = joinCodeText,
                        error = joinCodeError,
                        enabled = !joining && lookup == null,
                        onTextChange = {
                            joinCodeText = it.take(MAX_JOIN_CODE_INPUT)
                            joinCodeError = null
                        },
                        onPaste = {
                            JoinCode.readClipboard(activity)?.let {
                                joinCodeText = it.trim().take(MAX_JOIN_CODE_INPUT)
                                joinCodeError = null
                            }
                        },
                        onJoin = {
                            val parsed = JoinCode.parse(joinCodeText)
                            if (parsed == null) {
                                joinCodeError = "This code is not valid"
                            } else {
                                message = null
                                lookup = parsed
                            }
                        }
                    )
                }

                val publicRooms = directory.rooms.filter { !it.isPrivate }
                val privateRooms = directory.rooms.filter { it.isPrivate && !it.isHidden }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoomTabButton("Public Rooms (${publicRooms.size})", !showPrivate, Modifier.weight(1f)) {
                        showPrivate = false
                    }
                    RoomTabButton("Private Rooms (${privateRooms.size})", showPrivate, Modifier.weight(1f)) {
                        showPrivate = true
                    }
                }
                val shown = if (showPrivate) privateRooms else publicRooms
                if (shown.isEmpty()) {
                    Text(
                        if (showPrivate) "No private rooms right now. Use a join code to enter one."
                        else "No public rooms right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { room ->
                        key(room.id) {
                            RoomRow(room, canJoin = active == null && !joining && !room.isFull) {
                                nicknameError = null
                                codeError = null
                                lastCode = ""
                                codeFromJoinCode = false
                                joinTarget = room
                            }
                        }
                    }
                }
            }
        }
    }

    joinTarget?.let { room ->
        JoinDialog(
            title = "Join ${room.name}",
            room = room,
            initialNickname = Nickname.load(activity),
            initialCode = lastCode,
            needsCode = room.isPrivate && !codeFromJoinCode,
            nicknameError = nicknameError,
            codeError = codeError,
            onDismiss = {
                joinTarget = null
                nicknameError = null
                codeError = null
            },
            onConfirm = { nickname, code ->
                Nickname.save(activity, nickname)
                joinTarget = null
                nicknameError = null
                codeError = null
                lastCode = code
                join(room, nickname, code.takeIf { room.isPrivate })
            }
        )
    }

    if (showCreate) {
        CreateRoomDialog(
            initialNickname = Nickname.load(activity),
            onDismiss = { showCreate = false },
            onCreate = { nickname, name, game, maxPlayers, private, hidden ->
                showCreate = false
                Nickname.save(activity, nickname)
                preparingHost = true
                message = null
                val created = directory.createRoom(name, game, maxPlayers, nickname, private, hidden)
                if (created == null) {
                    preparingHost = false
                    message = "Could not create the room"
                } else {
                    // The VPN waits until STUN and the router port are done, so that port is the one we announce.
                    directory.whenHostReady { ok ->
                        preparingHost = false
                        if (ok) startTunnel(created.info, true, VpnConfig.HOST_ADDRESS, created.joinCode)
                    }
                }
            }
        )
    }
}

private fun DirectoryState.label() = when (this) {
    DirectoryState.IDLE -> "idle"
    DirectoryState.CONNECTING -> "connecting..."
    DirectoryState.ONLINE -> "online"
    DirectoryState.OFFLINE -> "offline"
}

@Composable
private fun ActiveRoomCard(
    active: ActiveRoom,
    members: List<RoomMember>,
    peers: Map<String, RoomEngine.PeerState>,
    onKick: (RoomMember) -> Unit,
    onLeave: () -> Unit
) {
    var pendingKick by remember { mutableStateOf<RoomMember?>(null) }
    val joinCode = active.code?.takeIf { active.isHost }

    pendingKick?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingKick = null },
            title = { Text("Remove player") },
            text = { Text("Remove ${target.name} from the room?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingKick = null
                    onKick(target)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingKick = null }) { Text("Cancel") } }
        )
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (active.isHost) "Hosting: ${active.room.name}" else "Joined: ${active.room.name}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (joinCode != null) CopyCodeButton(joinCode)
                    }
                    Text(
                        "Your address ${active.localAddress} - room ${active.room.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (joinCode != null) {
                        Text(
                            "Join code ${JoinCode.format(joinCode)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                OutlinedButton(onClick = onLeave) { Text("Leave") }
            }
            Text(
                "Players (${members.size}/${active.room.maxPlayers})",
                style = MaterialTheme.typography.labelLarge
            )
            members.forEach { member ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        member.name + if (member.isHost) " (host)" else "",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        connectionLabel(active, member, peers) + member.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (active.isHost && !member.isHost) {
                        TextButton(onClick = { pendingKick = member }) { Text("Kick") }
                    }
                }
            }
            connectionHint(active, peers)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RoomRow(room: RoomInfo, canJoin: Boolean, onJoin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (room.isPrivate) {
                        Icon(
                            Icons.Outlined.Lock, "Private room",
                            Modifier.padding(end = 6.dp).size(16.dp)
                        )
                    }
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    listOf(room.game, room.hostName).filter { it.isNotBlank() }.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("${room.players}/${room.maxPlayers}", modifier = Modifier.padding(horizontal = 12.dp))
            Button(onClick = onJoin, enabled = canJoin) { Text(if (room.isFull) "Full" else "Join") }
        }
    }
}

@Composable
private fun CreateRoomDialog(
    initialNickname: String,
    onDismiss: () -> Unit,
    onCreate: (nickname: String, name: String, game: String, maxPlayers: Int, private: Boolean, hidden: Boolean) -> Unit
) {
    var nickname by remember { mutableStateOf(initialNickname) }
    var name by remember {
        mutableStateOf(if (initialNickname.isEmpty()) "" else "$initialNickname's room".take(MAX_NAME_LENGTH))
    }
    var game by remember { mutableStateOf("") }
    var maxPlayers by remember { mutableFloatStateOf(8f) }
    var private by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    val valid = isValidNickname(nickname) && name.isNotBlank() &&
        TextFilter.isAllowed(name) && TextFilter.isAllowed(game)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NicknameField(nickname, { nickname = Nickname.limit(it) }, serverError = null)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = { Text("Room name") },
                    isError = !TextFilter.isAllowed(name),
                    supportingText = { if (!TextFilter.isAllowed(name)) Text(NOT_ALLOWED) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = game,
                    onValueChange = { game = it.take(MAX_NAME_LENGTH) },
                    label = { Text("Game (optional)") },
                    isError = !TextFilter.isAllowed(game),
                    supportingText = { if (!TextFilter.isAllowed(game)) Text(NOT_ALLOWED) },
                    singleLine = true
                )
                Text("Max players: ${maxPlayers.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = maxPlayers,
                    onValueChange = { maxPlayers = it },
                    valueRange = VpnConfig.MIN_PLAYERS.toFloat()..VpnConfig.MAX_PLAYERS.toFloat(),
                    steps = VpnConfig.MAX_PLAYERS - VpnConfig.MIN_PLAYERS - 1
                )
                Row(
                    Modifier.fillMaxWidth().clickable { private = !private },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = private, onCheckedChange = { private = it })
                    Column {
                        Text("Private", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (private) "Players need the join code to enter" else "Anyone can join from the list",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (private) {
                    Row(
                        Modifier.fillMaxWidth().clickable { hidden = !hidden },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                        Icon(Icons.Outlined.VisibilityOff, null, Modifier.padding(end = 8.dp).size(20.dp))
                        Column {
                            Text("Hidden", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Not shown in the list; players need the join code",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "You get a join code to share once the room is created",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onCreate(Nickname.clean(nickname), name.trim(), game.trim(), maxPlayers.toInt(), private, private && hidden)
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Asked when entering a room, so the player can change the nickname each time. The errors are
// the host's refusal of the last attempt (name already taken, wrong code); each stays until its
// field is edited.
@Composable
private fun JoinDialog(
    title: String,
    room: RoomInfo,
    initialNickname: String,
    initialCode: String,
    needsCode: Boolean,
    nicknameError: String?,
    codeError: String?,
    onDismiss: () -> Unit,
    onConfirm: (nickname: String, code: String) -> Unit
) {
    var nickname by remember { mutableStateOf(initialNickname) }
    var code by remember { mutableStateOf(initialCode) }
    var showNicknameError by remember { mutableStateOf(nicknameError != null) }
    var showCodeError by remember { mutableStateOf(codeError != null) }
    val shownNicknameError = nicknameError.takeIf { showNicknameError }
    val shownCodeError = codeError.takeIf { showCodeError }
    // The tag in the announcement lets a typo be caught here, without asking the host.
    val localCodeError = when {
        !needsCode || code.isEmpty() -> null
        !JoinCode.isValid(code) -> "The join code has ${JoinCode.LENGTH} letters and numbers"
        !JoinCode.matches(room, code) -> "This code does not belong to this room"
        else -> null
    }
    val codeOk = !needsCode || (JoinCode.isValid(code) && localCodeError == null && shownCodeError == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NicknameField(nickname, { nickname = Nickname.limit(it); showNicknameError = false }, shownNicknameError)
                if (needsCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = JoinCode.normalize(it); showCodeError = false },
                        label = { Text("Join code") },
                        isError = shownCodeError != null || localCodeError != null,
                        supportingText = { Text(shownCodeError ?: localCodeError ?: "Ask the host for the join code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValidNickname(nickname) && shownNicknameError == null && codeOk,
                onClick = { onConfirm(Nickname.clean(nickname), code) }
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RoomTabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CopyCodeButton(joinCode: String) {
    val context = LocalContext.current
    IconButton(onClick = { JoinCode.copyToClipboard(context, joinCode) }) {
        Icon(painterResource(R.drawable.vpn_ic_copy), "Copy join code")
    }
}

@Composable
private fun JoinCodeRow(
    text: String,
    error: String?,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onJoin: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Join with code") },
            isError = error != null,
            supportingText = { Text(error ?: "Paste the code the host shared") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onPaste, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Outlined.ContentPaste, "Paste")
        }
        Button(
            onClick = onJoin,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("Join") }
    }
}

@Composable
private fun NicknameField(value: String, onValueChange: (String) -> Unit, serverError: String?) {
    val problem = serverError ?: if (!TextFilter.isAllowed(value)) "That nickname is not allowed" else null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Nickname") },
        isError = problem != null,
        supportingText = { Text(problem ?: "Other players see this name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun isValidNickname(text: String): Boolean {
    val clean = Nickname.clean(text)
    return clean.isNotEmpty() && TextFilter.isAllowed(clean)
}

private const val MAX_NAME_LENGTH = 32
private const val MAX_JOIN_CODE_INPUT = 40
private const val LOOKUP_TIMEOUT_MS = 8_000L
private const val LOOKUP_STEP_MS = 250L
private const val NOT_ALLOWED = "This text is not allowed"
