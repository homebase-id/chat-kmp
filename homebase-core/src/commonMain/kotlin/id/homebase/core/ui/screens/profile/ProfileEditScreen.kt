@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalUuidApi::class,
    ExperimentalAnimationApi::class,
    ExperimentalLayoutApi::class,
)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.rememberImeOffsetState
import id.homebase.resources.ok
import id.homebase.resources.profile_edit_label
import id.homebase.resources.profile_edit_birthday_pick
import id.homebase.resources.profile_edit_not_on_card
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDirection
import id.homebase.resources.profile_edit_photo_public_caption
import id.homebase.resources.profile_edit_photo_only_me_caption
import id.homebase.resources.profile_edit_add_group_about
import id.homebase.resources.profile_edit_add_group_social
import id.homebase.resources.profile_edit_label_custom
import id.homebase.resources.profile_edit_label_home
import id.homebase.resources.profile_edit_label_mobile
import id.homebase.resources.profile_edit_label_personal
import id.homebase.resources.profile_edit_label_work
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CookieBadge
import id.homebase.core.ui.screens.card.WithTooltip
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.SettingsLargeTopBar
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_error_birthday
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_phone
import id.homebase.resources.profile_avatar_edit_error_delete
import id.homebase.resources.profile_avatar_edit_error_too_large
import id.homebase.resources.profile_avatar_edit_error_upload
import id.homebase.resources.profile_edit_action_cards
import id.homebase.resources.profile_edit_action_preview
import id.homebase.resources.profile_edit_add_attribute
import id.homebase.resources.profile_edit_add_attribute_title
import id.homebase.resources.profile_edit_add_named
import id.homebase.resources.profile_edit_additional_name
import id.homebase.resources.profile_edit_address1
import id.homebase.resources.profile_edit_address2
import id.homebase.resources.profile_edit_address_label
import id.homebase.resources.profile_edit_bio
import id.homebase.resources.profile_edit_birthday
import id.homebase.resources.profile_edit_birthday_hint
import id.homebase.resources.profile_edit_city
import id.homebase.resources.profile_edit_conflict_detail
import id.homebase.resources.profile_edit_conflict_remove
import id.homebase.resources.profile_edit_conflict_title
import id.homebase.resources.profile_edit_country
import id.homebase.resources.profile_edit_details_desc
import id.homebase.resources.profile_edit_details_title
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_email_label
import id.homebase.resources.profile_edit_empty_desc
import id.homebase.resources.profile_edit_error_forbidden
import id.homebase.resources.profile_edit_error_save
import id.homebase.resources.profile_edit_facebook
import id.homebase.resources.profile_edit_given_name
import id.homebase.resources.profile_edit_instagram
import id.homebase.resources.profile_edit_link
import id.homebase.resources.profile_edit_link_target
import id.homebase.resources.profile_edit_link_text
import id.homebase.resources.profile_edit_linkedin
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_load_failed_desc
import id.homebase.resources.profile_edit_nickname
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_phone_label
import id.homebase.resources.profile_edit_photos_desc
import id.homebase.resources.profile_edit_photos_title
import id.homebase.resources.profile_edit_postcode
import id.homebase.resources.profile_edit_preview_exit
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_title
import id.homebase.resources.profile_edit_twitter
import id.homebase.resources.remove
import id.homebase.resources.save
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    avatarViewModel: ProfileAvatarEditViewModel,
    onBack: () -> Unit,
    onNavigateToCropper: (Uuid) -> Unit,
    onOpenCard: (() -> Unit)?,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val avatarUiState by avatarViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewMode by remember { mutableStateOf(false) }

    val errForbidden = stringResource(MR.string.profile_edit_error_forbidden)
    val errSave = stringResource(MR.string.profile_edit_error_save)
    val errAvatarUpload = stringResource(MR.string.profile_avatar_edit_error_upload)
    val errAvatarTooLarge = stringResource(MR.string.profile_avatar_edit_error_too_large)
    val errAvatarDelete = stringResource(MR.string.profile_avatar_edit_error_delete)

    val anonymousPhotoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { avatarViewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.ANONYMOUS, it)) }
    }
    val onlyMePhotoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { avatarViewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.OWNER, it)) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEditEvent.AttributeSaved -> Unit
                ProfileEditEvent.Back -> onBack()
                ProfileEditEvent.Forbidden -> snackbarHostState.showSnackbar(errForbidden)
                ProfileEditEvent.Error -> snackbarHostState.showSnackbar(errSave)
            }
        }
    }

    LaunchedEffect(Unit) {
        avatarViewModel.events.collect { event ->
            when (event) {
                is ProfileAvatarEditEvent.NavigateToCropper -> onNavigateToCropper(event.requestId)
                ProfileAvatarEditEvent.Back -> Unit // this screen's own back arrow drives navigation, not the avatar VM's.
                is ProfileAvatarEditEvent.UploadFailed -> snackbarHostState.showSnackbar(errAvatarUpload)
                is ProfileAvatarEditEvent.UploadTooLarge -> snackbarHostState.showSnackbar(errAvatarTooLarge)
                is ProfileAvatarEditEvent.DeleteFailed -> snackbarHostState.showSnackbar(errAvatarDelete)
            }
        }
    }

    ProfileEditContent(
        uiState = uiState,
        avatarUiState = avatarUiState,
        previewMode = previewMode,
        onTogglePreview = { previewMode = !previewMode },
        onOpenCard = onOpenCard,
        onAction = viewModel::onAction,
        onAvatarAction = avatarViewModel::onAction,
        onPickAnonymousPhoto = { anonymousPhotoPicker.launch() },
        onPickOnlyMePhoto = { onlyMePhotoPicker.launch() },
        snackbarHostState = snackbarHostState,
    )
}

@Composable
internal fun ProfileEditContent(
    uiState: ProfileEditUiState,
    avatarUiState: ProfileAvatarEditUiState,
    previewMode: Boolean,
    onTogglePreview: () -> Unit,
    onOpenCard: (() -> Unit)?,
    onAction: (ProfileEditAction) -> Unit,
    onAvatarAction: (ProfileAvatarEditAction) -> Unit,
    onPickAnonymousPhoto: () -> Unit,
    onPickOnlyMePhoto: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    ProfileExpressiveType {
        val motion = MaterialTheme.motionScheme
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val ready = !uiState.isLoading && !uiState.loadFailed
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                SettingsLargeTopBar(
                    title = stringResource(MR.string.profile_edit_title),
                    onBack = { onAction(ProfileEditAction.BackClicked) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (ready) {
                            TopBarAction(
                                label = stringResource(
                                    if (previewMode) MR.string.profile_edit_preview_exit else MR.string.profile_edit_action_preview,
                                ),
                                icon = if (previewMode) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                                onClick = onTogglePreview,
                            )
                            if (onOpenCard != null) {
                                TopBarAction(
                                    label = stringResource(MR.string.profile_edit_action_cards),
                                    icon = Icons.Outlined.ContactPage,
                                    onClick = onOpenCard,
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when {
                uiState.isLoading -> ProfileEditSkeleton(Modifier.fillMaxSize().padding(padding))
                uiState.loadFailed -> LoadFailedState(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRetry = { onAction(ProfileEditAction.RetryLoadClicked) },
                )
                else -> {
                    AnimatedContent(
                        targetState = previewMode,
                        transitionSpec = {
                            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                        },
                    ) { preview ->
                        if (preview) {
                            ProfilePreview(
                                uiState = uiState,
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                ProfileForm(
                                    uiState = uiState,
                                    onAction = onAction,
                                    avatarUiState = avatarUiState,
                                    onAvatarAction = onAvatarAction,
                                    onPickAnonymousPhoto = onPickAnonymousPhoto,
                                    onPickOnlyMePhoto = onPickOnlyMePhoto,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AnimatedVisibility(
                                    visible = uiState.savingAttributes.isNotEmpty(),
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    enter = fadeIn(motion.defaultEffectsSpec()),
                                    exit = fadeOut(motion.defaultEffectsSpec()),
                                ) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    WithTooltip(label) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label)
        }
    }
}

/** The page's own layout in placeholder blocks, so content lands without a reflow. */
@Composable
internal fun ProfileEditSkeleton(modifier: Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    val shapes = MaterialTheme.shapes
    @Composable
    fun Bar(width: Dp, height: Dp, modifier: Modifier = Modifier) =
        Box(modifier.size(width, height).background(block, CircleShape))

    Column(modifier = modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Bar(128.dp, 24.dp)
            Bar(240.dp, 14.dp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(200.dp, 176.dp).background(block, shapes.extraLarge))
            Box(Modifier.size(120.dp, 176.dp).background(block, shapes.extraLarge))
            Box(Modifier.size(56.dp, 176.dp).background(block, shapes.extraLarge))
        }
        Bar(96.dp, 24.dp, Modifier.padding(start = 16.dp, top = 32.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) { Box(Modifier.weight(1f).height(168.dp).background(block, shapes.extraLarge)) }
        }
        Bar(96.dp, 24.dp, Modifier.padding(start = 16.dp, top = 32.dp))
        repeat(3) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(24.dp).background(block, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Bar(64.dp, 12.dp)
                    Bar(180.dp, 18.dp)
                    Bar(88.dp, 24.dp)
                }
            }
        }
    }
}

@Composable
internal fun LoadFailedState(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CookieBadge(
            icon = Icons.Outlined.ErrorOutline,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            size = 72.dp,
            iconSize = 32.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(MR.string.profile_edit_load_failed),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.profile_edit_load_failed_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.profile_edit_retry))
        }
    }
}

/** A row's own values and audience when it was opened, so Cancel can put them back. */
private class RowSnapshot(val values: Map<ProfileField, String>, val audience: ProfileAudience)

private val CORE_TYPES = setOf(
    ProfileAttributeTypes.NAME,
    ProfileAttributeTypes.PHONE,
    ProfileAttributeTypes.EMAIL,
    ProfileAttributeTypes.BIRTHDAY,
    ProfileAttributeTypes.BIO_SUMMARY,
)

private fun fieldsOf(type: String): List<ProfileField> =
    ProfileEditViewModel.TYPE_FIELDS[type].orEmpty().map { it.first }

/**
 * The fixed cards on top, then photos, then every detail once. Tapping a detail opens it in place
 * with its fields and the shared [AudiencePicker]; Save writes just that one attribute.
 */
@Composable
private fun ProfileForm(
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    avatarUiState: ProfileAvatarEditUiState,
    onAvatarAction: (ProfileAvatarEditAction) -> Unit,
    onPickAnonymousPhoto: () -> Unit,
    onPickOnlyMePhoto: () -> Unit,
    modifier: Modifier,
) {
    val openRows = remember { mutableStateMapOf<String, RowSnapshot>() }
    val openLinks = remember { mutableStateMapOf<String, LinkDraft>() }
    var showAddSheet by remember { mutableStateOf(false) }
    var openNewLink by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<String?>(null) }
    var pulse by remember { mutableStateOf(CardPulse()) }
    val motion = MaterialTheme.motionScheme

    val cards = remember(uiState.circles) { listOf(EditorCard(null)) + uiState.circles.map { EditorCard(it) } }
    val focusCard = cards.firstOrNull { it.key == selectedCard }
    fun onFocusedCard(audience: ProfileAudience) = focusCard == null || audience.isOnCard(focusCard.circle?.id)
    val focusName = focusCard?.let { cardLabel(it) }
    fun hiddenOn(audience: ProfileAudience): String? = focusName?.takeUnless { onFocusedCard(audience) }
    fun pulseCardsOf(audience: ProfileAudience) {
        pulse = CardPulse(pulse.tick + 1, cards.filter { audience.isOnCard(it.circle?.id) }.map { it.key }.toSet())
    }

    val present = ATTRIBUTE_SPECS.filter { displayValueFor(it.type, uiState.values) != null }
    val missing = ATTRIBUTE_SPECS - present.toSet()
    val savedLinks = uiState.links.filter { it.target.isNotBlank() || it.text.isNotBlank() }
    val profileEmpty = present.isEmpty() && savedLinks.isEmpty()
    val anyOpen = openRows.isNotEmpty() || openLinks.isNotEmpty()

    LaunchedEffect(uiState.links.size) {
        if (!openNewLink) return@LaunchedEffect
        uiState.links.lastOrNull { it.target.isBlank() && it.text.isBlank() }?.let { openLinks[it.key] = it }
        openNewLink = false
    }

    fun contentsOf(card: EditorCard): CardContents {
        val id = card.circle?.id
        val specs = present.filter { uiState.audience(it.type).isOnCard(id) }
        val links = savedLinks.filter { it.audience.isOnCard(id) }
        val name = profileNameValue(uiState.values)?.takeIf { uiState.audience(ProfileAttributeTypes.NAME).isOnCard(id) }
        val labels = specs.map { it.labelRes } + if (links.isEmpty()) emptyList() else listOf(MR.string.profile_edit_link)
        return CardContents(name, labels, specs.size + links.size)
    }

    val scroll = rememberScrollState()
    val ime = rememberImeOffsetState()
    // Large text leaves no room beside the FAB's label, so it stays a round button there.
    val roomyText = LocalDensity.current.fontScale < FAB_LABEL_MAX_FONT_SCALE
    val fabExpanded = roomyText && (!scroll.canScrollBackward || scroll.lastScrolledBackward)
    var popoverFor by remember { mutableStateOf<String?>(null) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Raw imePadding() leaves a home-indicator-high gap above the keyboard on iOS.
                .padding(bottom = with(ime.density) { ime.pureImeBottomPx.toDp() })
                .verticalScroll(scroll),
        ) {
            ProfileCardsStrip(
                cards = cards,
                contents = ::contentsOf,
                selected = selectedCard,
                onSelect = { selectedCard = it },
                pulse = pulse,
            )

            Spacer(Modifier.height(32.dp))
            SectionHeader(stringResource(MR.string.profile_edit_photos_title), stringResource(MR.string.profile_edit_photos_desc))
            if (!avatarUiState.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PhotoBlock(
                        audience = ProfileAudience.Public,
                        caption = stringResource(MR.string.profile_edit_photo_public_caption),
                        tier = ProfileVisibility.ANONYMOUS,
                        photoState = avatarUiState.anonymous,
                        onAvatarAction = onAvatarAction,
                        onPickPhoto = onPickAnonymousPhoto,
                        dimmed = !onFocusedCard(ProfileAudience.Public),
                        modifier = Modifier.weight(1f),
                    )
                    PhotoBlock(
                        audience = ProfileAudience.OnlyMe,
                        caption = stringResource(MR.string.profile_edit_photo_only_me_caption),
                        tier = ProfileVisibility.OWNER,
                        photoState = avatarUiState.onlyMe,
                        onAvatarAction = onAvatarAction,
                        onPickPhoto = onPickOnlyMePhoto,
                        dimmed = !onFocusedCard(ProfileAudience.OnlyMe),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ConnectionsPhotoBlock(avatarUiState.connected, onAvatarAction)

            Spacer(Modifier.height(32.dp))
            SectionHeader(
                stringResource(MR.string.profile_edit_details_title),
                stringResource(if (profileEmpty) MR.string.profile_edit_empty_desc else MR.string.profile_edit_details_desc),
            )
            ATTRIBUTE_SPECS.forEach { spec ->
                val display = displayValueFor(spec.type, uiState.values)
                    ?.let {
                        when (spec.type) {
                            ProfileAttributeTypes.PHONE -> formatPhoneForDisplay(it)
                            ProfileAttributeTypes.BIRTHDAY -> formatBirthday(it) ?: it
                            else -> it
                        }
                    }
                val snapshot = openRows[spec.type]
                if (display != null || snapshot != null || spec.type in CORE_TYPES) {
                    val audience = uiState.audience(spec.type)
                    val fields = fieldsOf(spec.type)
                    EditableFieldGroup(
                        icon = spec.icon,
                        label = stringResource(spec.labelRes),
                        displayValue = display,
                        audience = audience,
                        circles = uiState.circles,
                        otherNames = uiState.otherCircleNames,
                        snapshotAudience = snapshot?.audience,
                        popoverOpen = popoverFor == spec.type,
                        onOpenPopover = { popoverFor = spec.type },
                        onClosePopover = { popoverFor = null },
                        onSaveAudience = { picked ->
                            popoverFor = null
                            onAction(ProfileEditAction.AudienceChanged(spec.type, picked))
                            onAction(ProfileEditAction.SaveAttribute(spec.type))
                            pulseCardsOf(picked)
                        },
                        editing = snapshot != null,
                        hiddenOn = hiddenOn(audience),
                        onOpen = {
                            openRows[spec.type] = RowSnapshot(fields.associateWith { uiState.value(it) }, audience)
                        },
                        onCancel = {
                            snapshot?.let { s ->
                                s.values.forEach { (field, v) -> onAction(ProfileEditAction.FieldChanged(field, v)) }
                                onAction(ProfileEditAction.AudienceChanged(spec.type, s.audience))
                            }
                            openRows.remove(spec.type)
                        },
                        onSave = {
                            onAction(ProfileEditAction.SaveAttribute(spec.type))
                            pulseCardsOf(audience)
                            openRows.remove(spec.type)
                        },
                        onRemove = if (snapshot?.values?.values?.any { it.isNotBlank() } == true) {
                            {
                                fields.forEach { onAction(ProfileEditAction.FieldChanged(it, "")) }
                                onAction(ProfileEditAction.SaveAttribute(spec.type))
                                openRows.remove(spec.type)
                            }
                        } else {
                            null
                        },
                        onAudienceChange = { onAction(ProfileEditAction.AudienceChanged(spec.type, it)) },
                        canSave = isAttributeValid(spec.type) { uiState.value(it) } && audience.isSavableWith(uiState.circles),
                        conflicts = uiState.conflicts[spec.type].orEmpty(),
                        conflictType = spec.type,
                        onDiscardConflict = { onAction(ProfileEditAction.DiscardConflict(spec.type, it)) },
                    ) {
                        AttributeFields(spec.type, { uiState.value(it) }) { field, v ->
                            onAction(ProfileEditAction.FieldChanged(field, v))
                        }
                    }
                }
            }
            LinksSection(
                uiState = uiState,
                openLinks = openLinks,
                popoverFor = popoverFor,
                onPopover = { popoverFor = it },
                hiddenOn = ::hiddenOn,
                onSaved = ::pulseCardsOf,
                onAction = onAction,
            )
            // Clears the FAB (56dp) and its 16dp margin, plus a rhythm step, so the last row scrolls above it.
            Spacer(Modifier.height(104.dp))
        }

        AnimatedVisibility(
            // The audience popover floats over this corner, so the FAB steps aside while it is up.
            visible = !anyOpen && popoverFor == null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val addLabel = stringResource(MR.string.profile_edit_add_attribute)
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                expanded = fabExpanded,
                icon = { Icon(Icons.Filled.Add, contentDescription = if (fabExpanded) null else addLabel) },
                text = { Text(addLabel) },
            )
        }
    }

    if (showAddSheet) {
        AddAttributeSheet(
            missing = missing,
            onPick = { spec ->
                showAddSheet = false
                openRows[spec.type] = RowSnapshot(fieldsOf(spec.type).associateWith { uiState.value(it) }, uiState.audience(spec.type))
            },
            onPickLink = {
                showAddSheet = false
                openNewLink = true
                onAction(ProfileEditAction.AddLink)
            },
            onDismiss = { showAddSheet = false },
        )
    }
}

/** One photo slot in an expressive shape, with its audience pill underneath. */
@Composable
private fun PhotoBlock(
    audience: ProfileAudience,
    caption: String,
    tier: ProfileVisibility,
    photoState: PhotoTierUiState,
    onAvatarAction: (ProfileAvatarEditAction) -> Unit,
    onPickPhoto: () -> Unit,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    var photoRevealed by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, MaterialTheme.motionScheme.defaultEffectsSpec())
    Column(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraLarge)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhotoTierSection(
            tier = photoState,
            onPick = onPickPhoto,
            onRemove = { onAvatarAction(ProfileAvatarEditAction.RemoveClicked(tier)) },
            onSaveClicked = {
                onAvatarAction(ProfileAvatarEditAction.SaveClicked(tier))
                photoRevealed = false
            },
            controlsVisible = photoRevealed,
            centered = true,
            onPhotoTap = { photoRevealed = true },
            shape = MaterialShapes.Cookie9Sided.toShape(),
            idleBadge = true,
        ) {
            ExistingAvatarContent(photoState.existing, "ProfileEditScreen")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AudienceBadge(audience = audience, circles = emptyList())
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DIMMED_ALPHA = 0.6f
private const val FAB_LABEL_MAX_FONT_SCALE = 1.3f

/**
 * One detail, contact-detail style: icon, label, value and who sees it. Open, it lifts into a
 * rounded container with [content]'s fields, the shared [AudiencePicker] and its own
 * Remove / Cancel / Save. A detail with no value shows as an "add" row that opens the same editor.
 */
@Composable
private fun EditableFieldGroup(
    icon: ImageVector,
    label: String,
    displayValue: String?,
    audience: ProfileAudience,
    circles: List<CardCircle>,
    otherNames: Map<String, String>,
    snapshotAudience: ProfileAudience?,
    popoverOpen: Boolean,
    onOpenPopover: () -> Unit,
    onClosePopover: () -> Unit,
    onSaveAudience: (ProfileAudience) -> Unit,
    editing: Boolean,
    hiddenOn: String?,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onRemove: (() -> Unit)?,
    onAudienceChange: (ProfileAudience) -> Unit,
    canSave: Boolean,
    conflicts: List<ProfileAttribute> = emptyList(),
    conflictType: String = "",
    onDiscardConflict: (Uuid) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // The open row lifts into a rounded container; the corner and inset ride the spatial spring so it morphs rather than snaps.
    val isPlaceholder = displayValue.isNullOrBlank()
    // An empty detail is a tonal "add" tile; a filled one is flat until opened.
    val tile = editing || isPlaceholder
    val inset by animateDpAsState(
        when {
            editing -> 8.dp
            isPlaceholder -> 16.dp
            else -> 0.dp
        },
        motion.defaultSpatialSpec(),
    )
    val corner by animateDpAsState(
        when {
            editing -> 28.dp
            isPlaceholder -> 20.dp
            else -> 0.dp
        },
        motion.defaultSpatialSpec(),
    )
    val fillColor by animateColorAsState(
        when {
            editing -> colors.surfaceContainer
            isPlaceholder -> colors.surfaceContainerLow
            else -> colors.surface.copy(alpha = 0f)
        },
        motion.defaultEffectsSpec(),
    )
    val dimmed = hiddenOn != null && !editing
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, motion.defaultEffectsSpec())
    val shape = RoundedCornerShape(corner)
    val bringIntoView = remember { BringIntoViewRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .padding(horizontal = inset, vertical = if (tile) 4.dp else 0.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(fillColor, shape)
            .clip(shape),
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (editing) Modifier else Modifier.clickable(onClick = onOpen)),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = if (editing) {
                null
            } else {
                {
                    if (isPlaceholder) {
                        CookieIcon(icon)
                    } else {
                        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                }
            },
            overlineContent = if (tile) null else { { Text(label) } },
            headlineContent = {
                when {
                    editing -> PanelHeader(icon = icon, title = label)
                    isPlaceholder -> Text(label, style = MaterialTheme.typography.titleMediumEmphasized, color = colors.onSurface)
                    else -> Text(displayValue, color = colors.onSurface)
                }
            },
            supportingContent = if (!editing && !isPlaceholder) {
                {
                    // The popover anchors to this box, so it opens against the pill that summoned it.
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        AudienceBadge(audience = audience, circles = circles, otherNames = otherNames, onClick = onOpenPopover)
                        if (popoverOpen) {
                            AudiencePopover(
                                title = label,
                                icon = icon,
                                initial = audience,
                                circles = circles,
                                otherNames = otherNames,
                                onSave = onSaveAudience,
                                onDismiss = onClosePopover,
                            )
                        }
                    }
                }
            } else {
                null
            },
            trailingContent = when {
                editing -> null
                isPlaceholder -> {
                    {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(MR.string.profile_edit_add_named, label),
                            tint = colors.primary,
                        )
                    }
                }
                hiddenOn != null -> { { NotOnCardMarker(hiddenOn) } }
                else -> null
            },
        )
        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            LaunchedEffect(Unit) {
                snapshotFlow { transition.currentState == EnterExitState.Visible }.first { it }
                // A row added from the sheet starts open, so wait for its first layout before scrolling to it.
                withFrameNanos { }
                bringIntoView.bringIntoView()
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                AudiencePicker(
                    audience = audience,
                    circles = circles,
                    onChange = onAudienceChange,
                    otherCircles = otherCirclesOf(snapshotAudience ?: audience, audience, names = otherNames),
                )
                conflicts.forEach { record ->
                    ConflictRow(
                        value = conflictValue(conflictType, record),
                        audience = audienceSummary(record.audience(circles), circles, otherNames),
                        onDiscard = { onDiscardConflict(record.id) },
                    )
                }
                EditorActions(canSave = canSave, onSave = onSave, onCancel = onCancel, onRemove = onRemove)
            }
        }
    }
}

/** Why a row is dimmed while a card is focused: it is not on that card. */
@Composable
private fun NotOnCardMarker(cardName: String) {
    Row(
        modifier = Modifier.widthIn(max = 132.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(MR.string.profile_edit_not_on_card, cardName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorActions(canSave: Boolean, onSave: () -> Unit, onCancel: () -> Unit, onRemove: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onRemove != null) {
            val removeLabel = stringResource(MR.string.remove)
            WithTooltip(removeLabel) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = removeLabel, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.cancel))
        }
        Spacer(Modifier.width(8.dp))
        Button(enabled = canSave, onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.save), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun LinksSection(
    uiState: ProfileEditUiState,
    openLinks: MutableMap<String, LinkDraft>,
    popoverFor: String?,
    onPopover: (String?) -> Unit,
    hiddenOn: (ProfileAudience) -> String?,
    onSaved: (ProfileAudience) -> Unit,
    onAction: (ProfileEditAction) -> Unit,
) {
    val label = stringResource(MR.string.profile_edit_link)
    uiState.links.forEach { link ->
        val key = link.key
        val snapshot = openLinks[key]
        val display = link.text.ifBlank { link.target }.ifBlank { null }
        if (display == null && snapshot == null) return@forEach
        EditableFieldGroup(
            icon = Icons.Outlined.Link,
            label = label,
            displayValue = display,
            audience = link.audience,
            circles = uiState.circles,
            otherNames = uiState.otherCircleNames,
            snapshotAudience = snapshot?.audience,
            popoverOpen = popoverFor == key,
            onOpenPopover = { onPopover(key) },
            onClosePopover = { onPopover(null) },
            onSaveAudience = { picked ->
                onPopover(null)
                onAction(ProfileEditAction.LinkAudienceChanged(key, picked))
                onAction(ProfileEditAction.SaveLink(key))
                onSaved(picked)
            },
            editing = snapshot != null,
            hiddenOn = hiddenOn(link.audience),
            onOpen = { openLinks[key] = link },
            onCancel = {
                snapshot?.let { s ->
                    onAction(ProfileEditAction.LinkChanged(key, s.text, s.target))
                    onAction(ProfileEditAction.LinkAudienceChanged(key, s.audience))
                }
                openLinks.remove(key)
            },
            onSave = {
                onAction(ProfileEditAction.SaveLink(key))
                onSaved(link.audience)
                openLinks.remove(key)
            },
            onRemove = {
                onAction(ProfileEditAction.RemoveLink(key))
                openLinks.remove(key)
            },
            onAudienceChange = { onAction(ProfileEditAction.LinkAudienceChanged(key, it)) },
            canSave = link.isValid(uiState.circles),
        ) {
            ProfileField(
                link.text,
                stringResource(MR.string.profile_edit_link_text),
                modifier = Modifier.fillMaxWidth(),
            ) { onAction(ProfileEditAction.LinkChanged(key, it, link.target)) }
            ProfileField(
                link.target,
                stringResource(MR.string.profile_edit_link_target),
                keyboardType = KeyboardType.Uri,
                ltr = true,
                modifier = Modifier.fillMaxWidth(),
            ) { onAction(ProfileEditAction.LinkChanged(key, link.text, it)) }
        }
    }
}

/** What a record that disagrees with the edited one holds, for showing the user before they discard it. */
private fun conflictValue(type: String, record: ProfileAttribute): String =
    ProfileEditViewModel.TYPE_FIELDS[type].orEmpty()
        .mapNotNull { (_, key) -> record.string(key)?.ifBlank { null } }
        .joinToString(", ")

@Composable
private fun ConflictRow(value: String, audience: String, onDiscard: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.string.profile_edit_conflict_title, value),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(MR.string.profile_edit_conflict_detail, audience),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDiscard) { Text(stringResource(MR.string.profile_edit_conflict_remove)) }
    }
}

@Composable
private fun ProfileField(
    value: String,
    label: String,
    showLabel: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    errorText: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    ltr: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        // Addresses and URLs read left to right in every locale.
        textStyle = if (ltr) LocalTextStyle.current.copy(textDirection = TextDirection.Ltr) else LocalTextStyle.current,
        label = if (showLabel) { { Text(label) } } else null,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        supportingText = if (isError && errorText != null) { { Text(errorText) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        // The panel header names a lone field, so the label moves into its semantics.
        modifier = if (showLabel) modifier else modifier.semantics { contentDescription = label },
    )
}

/** One attribute type the "add detail" sheet can offer; its editable fields live in [AttributeFields]. */
internal data class AttributeSpec(
    val type: String,
    val icon: ImageVector,
    val labelRes: StringResource,
    val social: Boolean = false,
)

internal val ATTRIBUTE_SPECS = listOf(
    AttributeSpec(ProfileAttributeTypes.NAME, Icons.Outlined.Person, MR.string.contactbook_detail_name),
    AttributeSpec(ProfileAttributeTypes.NICKNAME, Icons.Outlined.Badge, MR.string.profile_edit_nickname),
    AttributeSpec(ProfileAttributeTypes.STATUS, Icons.Outlined.Mood, MR.string.profile_edit_status),
    AttributeSpec(ProfileAttributeTypes.BIRTHDAY, Icons.Outlined.Cake, MR.string.profile_edit_birthday),
    AttributeSpec(ProfileAttributeTypes.BIO_SUMMARY, Icons.AutoMirrored.Outlined.Notes, MR.string.profile_edit_bio),
    AttributeSpec(ProfileAttributeTypes.EMAIL, Icons.Outlined.Email, MR.string.profile_edit_email),
    AttributeSpec(ProfileAttributeTypes.PHONE, Icons.Outlined.Call, MR.string.profile_edit_phone),
    AttributeSpec(ProfileAttributeTypes.ADDRESS, Icons.Outlined.LocationOn, MR.string.contactbook_detail_location),
    AttributeSpec(ProfileAttributeTypes.TWITTER, Icons.Outlined.Tag, MR.string.profile_edit_twitter, social = true),
    AttributeSpec(ProfileAttributeTypes.FACEBOOK, Icons.Outlined.ThumbUp, MR.string.profile_edit_facebook, social = true),
    AttributeSpec(ProfileAttributeTypes.INSTAGRAM, Icons.Outlined.PhotoCamera, MR.string.profile_edit_instagram, social = true),
    AttributeSpec(ProfileAttributeTypes.TIKTOK, Icons.Outlined.MusicNote, MR.string.profile_edit_tiktok, social = true),
    AttributeSpec(ProfileAttributeTypes.LINKEDIN, Icons.Outlined.WorkOutline, MR.string.profile_edit_linkedin, social = true),
)

/** Whether [type] has a value in [values]; the rows and the add sheet's "missing" list both read it. */
private fun displayValueFor(type: String, values: Map<ProfileField, String>): String? = when (type) {
    ProfileAttributeTypes.NAME -> profileNameValue(values)
    ProfileAttributeTypes.NICKNAME -> values[ProfileField.NICKNAME]?.ifBlank { null }
    ProfileAttributeTypes.STATUS -> values[ProfileField.STATUS]?.ifBlank { null }
    ProfileAttributeTypes.BIRTHDAY -> values[ProfileField.BIRTHDAY]?.ifBlank { null }
    ProfileAttributeTypes.BIO_SUMMARY -> values[ProfileField.BIO]?.ifBlank { null }
    ProfileAttributeTypes.EMAIL -> values[ProfileField.EMAIL]?.ifBlank { null }
    ProfileAttributeTypes.PHONE -> values[ProfileField.PHONE]?.ifBlank { null }
    ProfileAttributeTypes.ADDRESS -> profileAddressValue(values)
    ProfileAttributeTypes.TWITTER -> values[ProfileField.TWITTER]?.ifBlank { null }
    ProfileAttributeTypes.FACEBOOK -> values[ProfileField.FACEBOOK]?.ifBlank { null }
    ProfileAttributeTypes.INSTAGRAM -> values[ProfileField.INSTAGRAM]?.ifBlank { null }
    ProfileAttributeTypes.TIKTOK -> values[ProfileField.TIKTOK]?.ifBlank { null }
    ProfileAttributeTypes.LINKEDIN -> values[ProfileField.LINKEDIN]?.ifBlank { null }
    else -> null
}

@Composable
internal fun AddAttributeSheet(
    missing: List<AttributeSpec>,
    onPick: (AttributeSpec) -> Unit,
    onPickLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.string.profile_edit_add_attribute_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            )
            val (social, about) = missing.partition { it.social }
            if (about.isNotEmpty()) {
                AddSheetGroup(stringResource(MR.string.profile_edit_add_group_about))
                about.forEach { spec ->
                    AddAttributeRow(spec.icon, stringResource(spec.labelRes), social = false) { dismiss { onPick(spec) } }
                }
            }
            AddSheetGroup(stringResource(MR.string.profile_edit_add_group_social))
            social.forEach { spec ->
                AddAttributeRow(spec.icon, stringResource(spec.labelRes), social = true) { dismiss { onPick(spec) } }
            }
            AddAttributeRow(Icons.Outlined.Link, stringResource(MR.string.profile_edit_link), social = true) { dismiss { onPickLink() } }
        }
    }
}

@Composable
private fun AddSheetGroup(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AddAttributeRow(icon: ImageVector, label: String, social: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            CookieBadge(
                icon = icon,
                container = if (social) colors.tertiaryContainer else colors.secondaryContainer,
                content = if (social) colors.onTertiaryContainer else colors.onSecondaryContainer,
                size = 44.dp,
                iconSize = 22.dp,
            )
        },
        headlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}


/** Whether [type]'s current draft is well-formed enough to save — only Email/Phone/Birthday
 *  constrain format; every other attribute type accepts anything (including blank, which just
 *  no-ops). */
private fun isAttributeValid(type: String, value: (ProfileField) -> String): Boolean = when (type) {
    ProfileAttributeTypes.EMAIL -> ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL))
    ProfileAttributeTypes.PHONE -> ContactFieldValidation.isValidPhone(value(ProfileField.PHONE))
    ProfileAttributeTypes.BIRTHDAY ->
        ContactFieldValidation.isValidBirthday(value(ProfileField.BIRTHDAY))
    else -> true
}

/**
 * The editable field(s) for one attribute type, bound generically via [value]/[onChange] so both an
 * existing row's inline editor and a newly added one share the exact same field UI.
 */
@Composable
private fun AttributeFields(
    type: String,
    value: (ProfileField) -> String,
    onChange: (ProfileField, String) -> Unit,
) {
    when (type) {
        ProfileAttributeTypes.NAME -> {
            ProfileField(
                value(ProfileField.GIVEN_NAME),
                stringResource(MR.string.profile_edit_given_name),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.GIVEN_NAME, it) }
            ProfileField(
                value(ProfileField.SURNAME),
                stringResource(MR.string.profile_edit_surname),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.SURNAME, it) }
            ProfileField(
                value(ProfileField.ADDITIONAL_NAME),
                stringResource(MR.string.profile_edit_additional_name),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDITIONAL_NAME, it) }
        }

        ProfileAttributeTypes.NICKNAME -> {
            ProfileField(
                value(ProfileField.NICKNAME),
                stringResource(MR.string.profile_edit_nickname),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.NICKNAME, it) }
        }

        ProfileAttributeTypes.STATUS -> {
            ProfileField(
                value(ProfileField.STATUS),
                stringResource(MR.string.profile_edit_status),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.STATUS, it) }
        }

        ProfileAttributeTypes.BIO_SUMMARY -> {
            ProfileField(
                value(ProfileField.BIO),
                stringResource(MR.string.profile_edit_bio),
                showLabel = false,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.BIO, it) }
        }

        ProfileAttributeTypes.BIRTHDAY -> {
            BirthdayField(value(ProfileField.BIRTHDAY)) { onChange(ProfileField.BIRTHDAY, it) }
        }

        ProfileAttributeTypes.EMAIL -> {
            val emailValue = value(ProfileField.EMAIL)
            ProfileField(
                value = emailValue,
                label = stringResource(MR.string.profile_edit_email),
                showLabel = false,
                keyboardType = KeyboardType.Email,
                ltr = true,
                isError = emailValue.isNotBlank() && !ContactFieldValidation.isValidEmail(emailValue),
                errorText = stringResource(MR.string.contactbook_error_email),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.EMAIL, it) }
            LabelChips(
                value = value(ProfileField.EMAIL_LABEL),
                presets = listOf(MR.string.profile_edit_label_personal, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_email_label),
            ) { onChange(ProfileField.EMAIL_LABEL, it) }
        }

        ProfileAttributeTypes.PHONE -> {
            val phoneValue = value(ProfileField.PHONE)
            val phoneLabel = stringResource(MR.string.profile_edit_phone)
            PhoneNumberField(
                e164Value = phoneValue,
                onValueChange = { onChange(ProfileField.PHONE, it) },
                label = null,
                isError = phoneValue.isNotBlank() && !ContactFieldValidation.isValidPhone(phoneValue),
                errorText = stringResource(MR.string.contactbook_error_phone),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = phoneLabel },
            )
            LabelChips(
                value = value(ProfileField.PHONE_LABEL),
                presets = listOf(MR.string.profile_edit_label_mobile, MR.string.profile_edit_label_home, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_phone_label),
            ) { onChange(ProfileField.PHONE_LABEL, it) }
        }

        ProfileAttributeTypes.ADDRESS -> {
            LabelChips(
                value = value(ProfileField.ADDRESS_LABEL),
                presets = listOf(MR.string.profile_edit_label_home, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_address_label),
            ) { onChange(ProfileField.ADDRESS_LABEL, it) }
            ProfileField(
                value = value(ProfileField.ADDRESS1),
                label = stringResource(MR.string.profile_edit_address1),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDRESS1, it) }
            ProfileField(
                value = value(ProfileField.ADDRESS2),
                label = stringResource(MR.string.profile_edit_address2),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDRESS2, it) }
            ProfileField(
                value = value(ProfileField.POSTCODE),
                label = stringResource(MR.string.profile_edit_postcode),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.POSTCODE, it) }
            ProfileField(
                value = value(ProfileField.CITY),
                label = stringResource(MR.string.profile_edit_city),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.CITY, it) }
            ProfileField(
                value = value(ProfileField.COUNTRY),
                label = stringResource(MR.string.profile_edit_country),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.COUNTRY, it) }
        }

        ProfileAttributeTypes.TWITTER -> {
            ProfileField(
                value(ProfileField.TWITTER),
                stringResource(MR.string.profile_edit_twitter),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TWITTER, it) }
        }
        ProfileAttributeTypes.FACEBOOK -> {
            ProfileField(
                value(ProfileField.FACEBOOK),
                stringResource(MR.string.profile_edit_facebook),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.FACEBOOK, it) }
        }
        ProfileAttributeTypes.INSTAGRAM -> {
            ProfileField(
                value(ProfileField.INSTAGRAM),
                stringResource(MR.string.profile_edit_instagram),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.INSTAGRAM, it) }
        }
        ProfileAttributeTypes.TIKTOK -> {
            ProfileField(
                value(ProfileField.TIKTOK),
                stringResource(MR.string.profile_edit_tiktok),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TIKTOK, it) }
        }
        ProfileAttributeTypes.LINKEDIN -> {
            ProfileField(
                value(ProfileField.LINKEDIN),
                stringResource(MR.string.profile_edit_linkedin),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.LINKEDIN, it) }
        }
    }
}

/**
 * A detail's label as one tap: a connected group of presets plus Custom, which opens a field.
 * Tapping the picked segment again clears the label.
 */
@Composable
private fun LabelChips(
    value: String,
    presets: List<StringResource>,
    customLabel: String,
    onChange: (String) -> Unit,
) {
    val names = presets.map { stringResource(it) }
    var custom by remember { mutableStateOf(value.isNotBlank() && names.none { it.equals(value, ignoreCase = true) }) }
    var focusCustom by remember { mutableStateOf(false) }
    val customField = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceCaption(stringResource(MR.string.profile_edit_label))
        ConnectedChoices(count = names.size + 1) { index, shapes, sizing ->
            val isCustom = index == names.size
            val picked = if (isCustom) custom else !custom && names[index].equals(value, ignoreCase = true)
            ToggleButton(
                checked = picked,
                onCheckedChange = {
                    if (isCustom) {
                        custom = !custom
                        focusCustom = custom
                        if (!custom || names.any { it.equals(value, ignoreCase = true) }) onChange("")
                    } else {
                        custom = false
                        onChange(if (picked) "" else names[index])
                    }
                },
                shapes = shapes,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = sizing.semantics { role = Role.RadioButton },
            ) {
                Text(
                    text = if (isCustom) stringResource(MR.string.profile_edit_label_custom) else names[index],
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = custom,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            // Focused once fully open, so it lands above the keyboard rather than growing under it.
            LaunchedEffect(Unit) {
                if (!focusCustom) return@LaunchedEffect
                snapshotFlow { transition.currentState == EnterExitState.Visible }.first { it }
                customField.requestFocus()
                focusCustom = false
            }
            ProfileField(value = value, label = customLabel, modifier = Modifier.fillMaxWidth().focusRequester(customField), onChange = onChange)
        }
    }
}

/** The ISO birthday as the locale writes dates; null when it is not a valid date. */
internal fun formatBirthday(iso: String): String? {
    val date = runCatching { LocalDate.parse(iso.trim()) }.getOrNull() ?: return null
    // Noon keeps any time zone from turning the day over.
    return formatMediumDate(date.atTime(12, 0).toInstant(TimeZone.currentSystemDefault()))
}

/** A birthday is picked from the calendar, never typed; a stored value that is not a date still shows, flagged. */
@Composable
private fun BirthdayField(value: String, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val label = stringResource(MR.string.profile_edit_birthday)
    val invalid = value.isNotBlank() && !ContactFieldValidation.isValidBirthday(value)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (value.isBlank()) "" else formatBirthday(value) ?: value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = { Text(stringResource(MR.string.profile_edit_birthday_pick)) },
            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            isError = invalid,
            supportingText = if (invalid) { { Text(stringResource(MR.string.contactbook_error_birthday)) } } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Over the whole field, so any tap opens the calendar instead of placing a cursor.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button, onClickLabel = label) { picking = true }
                .semantics { contentDescription = label },
        )
    }
    if (picking) {
        val initial = remember(value) {
            runCatching { LocalDate.parse(value.trim()) }.getOrNull()
                ?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        // The picker answers in UTC midnight of the picked day.
                        state.selectedDateMillis?.let {
                            onChange(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString())
                        }
                        picking = false
                    },
                ) { Text(stringResource(MR.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(MR.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
