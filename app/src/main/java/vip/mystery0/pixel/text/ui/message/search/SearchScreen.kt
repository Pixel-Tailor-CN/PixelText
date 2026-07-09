package vip.mystery0.pixel.text.ui.message.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.util.SimInfoProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onResultClick: (MessageModel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchFilter by viewModel.searchFilter.collectAsState()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val simList = remember { SimInfoProvider.getActiveSimList(context).take(2) }
    val firstSim = simList.getOrNull(0)
    val secondSim = simList.getOrNull(1)
    var showContactPermissionDialog by remember { mutableStateOf(false) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        val selectedContact = resolvePickedContact(context, uri)
        if (selectedContact != null) {
            viewModel.setContactFilter(
                address = selectedContact.address,
                displayName = selectedContact.displayName
            )
        } else if (uri != null) {
            Toast.makeText(context, "未读取到联系人号码", Toast.LENGTH_SHORT).show()
        }
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            Toast.makeText(context, "需要联系人权限才能选择联系人", Toast.LENGTH_SHORT).show()
        }
    }

    val contactDefaultSmsAppLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    fun requestContactPermissionAfterDefaultPrompt() {
        if (context.isDefaultSmsApp()) {
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            contactDefaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent())
        }
    }

    fun openContactPicker() {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactPickerLauncher.launch(null)
        } else {
            showContactPermissionDialog = true
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = viewModel::updateQuery,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "搜索短信",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "清空搜索内容"
                                )
                            }
                        }
                        IconButton(onClick = ::openContactPicker) {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = "选择联系人"
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.updateQuery("")
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                searchFilter.contactAddress?.let { contactAddress ->
                    FilterChip(
                        selected = true,
                        onClick = viewModel::clearContactFilter,
                        label = {
                            Text(
                                text = searchFilter.contactDisplayName ?: contactAddress,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "移除联系人筛选",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                FilterChip(
                    selected = searchFilter.unreadOnly,
                    onClick = viewModel::toggleUnreadFilter,
                    label = { Text("未读") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.simSubId == firstSim?.subscriptionId,
                    onClick = {
                        firstSim?.let { viewModel.toggleSimFilter(it.subscriptionId) }
                    },
                    enabled = firstSim != null,
                    label = { Text(firstSim?.displayName ?: "卡一") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.simSubId == secondSim?.subscriptionId,
                    onClick = {
                        secondSim?.let { viewModel.toggleSimFilter(it.subscriptionId) }
                    },
                    enabled = secondSim != null,
                    label = { Text(secondSim?.displayName ?: "卡二") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.mmsOnly,
                    onClick = viewModel::toggleMmsFilter,
                    label = { Text("彩信") }
                )
            }

            SearchResultList(
                uiState = uiState,
                query = searchQuery,
                onResultClick = onResultClick
            )

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (showContactPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showContactPermissionDialog = false },
            title = { Text("需要联系人权限") },
            text = {
                Text(
                    "PixelText 需要联系人权限来打开联系人选择器并筛选短信。点击申请后，如尚未设置默认短信应用，会先显示默认短信应用请求，再显示系统权限请求。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showContactPermissionDialog = false
                        requestContactPermissionAfterDefaultPrompt()
                    }
                ) {
                    Text("申请")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactPermissionDialog = false }) {
                    Text("稍后")
                }
            }
        )
    }
}

private data class PickedContact(
    val address: String,
    val displayName: String?
)

private fun resolvePickedContact(context: Context, contactUri: Uri?): PickedContact? {
    if (contactUri == null) return null

    val contactId = context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getLong(0)
        } else {
            null
        }
    } ?: return null

    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        null
    )?.use { cursor ->
        val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val nameIndex =
            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val number = cursor.getString(numberIndex).orEmpty()
            if (number.isBlank()) continue
            val displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
            return PickedContact(
                address = number,
                displayName = displayName
            )
        }
    }
    return null
}
