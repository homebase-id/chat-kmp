package id.homebase.core.ui.screens.email.thunderbird

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.core.email.Thunderbird
import id.homebase.core.ui.screens.email.components.MailSettingsCard
import id.homebase.core.util.isExpandedLayout
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_save_private_key
import id.homebase.resources.email_settings_incoming
import id.homebase.resources.email_settings_outgoing
import id.homebase.resources.email_tb_copy_address
import id.homebase.resources.email_tb_copy_password
import id.homebase.resources.email_tb_copy_public_key
import id.homebase.resources.email_tb_open_keyserver
import id.homebase.resources.email_tb_get_fdroid
import id.homebase.resources.email_tb_get_play
import id.homebase.resources.email_tb_manual_note
import id.homebase.resources.email_tb_page_of
import id.homebase.resources.email_tb_shot_zoom
import id.homebase.resources.email_tb_sb_account_body
import id.homebase.resources.email_tb_sb_account_title
import id.homebase.resources.email_tb_sb_address_body
import id.homebase.resources.email_tb_sb_address_title
import id.homebase.resources.email_tb_sb_allow_body
import id.homebase.resources.email_tb_sb_allow_title
import id.homebase.resources.email_tb_sb_config_body
import id.homebase.resources.email_tb_sb_config_title
import id.homebase.resources.email_tb_sb_delete_body
import id.homebase.resources.email_tb_sb_delete_title
import id.homebase.resources.email_tb_sb_done_body
import id.homebase.resources.email_tb_sb_done_keyserver
import id.homebase.resources.email_tb_sb_done_note
import id.homebase.resources.email_tb_sb_done_title
import id.homebase.resources.email_tb_sb_encrypted_body
import id.homebase.resources.email_tb_sb_encrypted_title
import id.homebase.resources.email_tb_sb_folders_body
import id.homebase.resources.email_tb_sb_folders_title
import id.homebase.resources.email_tb_sb_import_body
import id.homebase.resources.email_tb_sb_import_title
import id.homebase.resources.email_tb_sb_importstart_body
import id.homebase.resources.email_tb_sb_importstart_title
import id.homebase.resources.email_tb_sb_pick_body
import id.homebase.resources.email_tb_sb_pick_title
import id.homebase.resources.email_tb_sb_install_body
import id.homebase.resources.email_tb_sb_install_title
import id.homebase.resources.email_tb_sb_okc_body
import id.homebase.resources.email_tb_sb_okc_title
import id.homebase.resources.email_tb_sb_savekey_body
import id.homebase.resources.email_tb_sb_savekey_title
import id.homebase.resources.email_tb_sb_test_body
import id.homebase.resources.email_tb_sb_test_title
import id.homebase.resources.menu_back
import id.homebase.resources.tb_sb_account
import id.homebase.resources.tb_sb_address
import id.homebase.resources.tb_sb_allow
import id.homebase.resources.tb_sb_config
import id.homebase.resources.tb_sb_encrypted
import id.homebase.resources.tb_sb_folders
import id.homebase.resources.tb_sb_import
import id.homebase.resources.tb_sb_importstart
import id.homebase.resources.tb_sb_pick
import id.homebase.resources.tb_sb_install
import id.homebase.resources.tb_sb_okc
import id.homebase.resources.tb_sb_savekey
import id.homebase.resources.tb_sb_test
import id.homebase.resources.tb_sb_done
import id.homebase.resources.next
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Thunderbird setup on Android, one of its screens per page, in the order they actually appear.
 *
 * A flat list of steps loses the thread here because the flow crosses three apps and doubles
 * back: Thunderbird sends you to Google Play, Play to OpenKeychain, OpenKeychain back to
 * Thunderbird. Paging it keeps "where am I" answerable, and each page carries the one value its
 * instruction asks the user to produce.
 *
 * Two pages exist purely to stop people concluding it is broken: mail arriving as *Encrypted*
 * before OpenKeychain is installed, and OpenKeychain showing the imported key in red as a secret
 * key with no name.
 *
 * Each page carries the screenshot of the screen it describes, taken on a real run-through. They
 * are the point of the storyboard rather than decoration: someone who cannot find "End-to-end
 * encryption" in Thunderbird's settings recognises the picture long before they parse the
 * sentence. All twelve together are ~210 KB as 540px WebP.
 */
@Composable
internal fun EmailThunderbirdStoryboard(
    actions: ThunderbirdActions,
    modifier: Modifier = Modifier,
) {
    val pages = storyboardPages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(
                MR.string.email_tb_page_of,
                pagerState.currentPage + 1,
                pages.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SetupCard(title = stringResource(page.title)) {
                    Body(stringResource(page.body))
                    // Anything the user does not have to read to finish the step goes last, after
                    // the picture, so the instruction and the screen it names stay together.
                    page.notes.forEach { note -> Body(stringResource(note)) }
                    PageShot(image = page.image, label = stringResource(page.title))
                    PageExtras(extras = page.extras, actions = actions)
                }
            }
        }

        PageDots(count = pages.size, current = pagerState.currentPage)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                enabled = pagerState.currentPage > 0,
            ) {
                Text(stringResource(MR.string.menu_back))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                enabled = pagerState.currentPage < pages.lastIndex,
            ) {
                Text(stringResource(MR.string.next))
            }
        }
    }
}

/**
 * The screen this page is about. Bounded by height, not width: these are portrait phone captures,
 * so a width-driven fit would make them taller than the page.
 *
 * Inline it is a reminder, not a document — phone UI shrunk to a card is not readable, least of
 * all on a desktop pane where the card is wide and the picture ends up small in the middle of it.
 * So it opens full-screen on tap, and the assets stay at capture resolution to survive that.
 */
@Composable
private fun PageShot(image: DrawableResource, label: String) {
    var zoomed by remember(image) { mutableStateOf(false) }
    val painter = painterResource(image)

    Spacer(modifier = Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painter,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = if (isExpandedLayout()) 560.dp else 380.dp)
                .clip(MaterialTheme.shapes.medium)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.medium,
                )
                .clickable { zoomed = true },
        )
    }
    Text(
        text = stringResource(MR.string.email_tb_shot_zoom),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = TextAlign.Center,
    )

    if (zoomed) {
        Dialog(
            onDismissRequest = { zoomed = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Image(
                painter = painter,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable { zoomed = false }
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun PageExtras(extras: PageExtras, actions: ThunderbirdActions) {
    when (extras) {
        PageExtras.NONE -> Unit

        PageExtras.INSTALL_THUNDERBIRD -> ActionRow {
            StepAction(stringResource(MR.string.email_tb_get_play)) {
                actions.onOpenUrl(Thunderbird.PLAY_URL)
            }
            StepAction(stringResource(MR.string.email_tb_get_fdroid)) {
                actions.onOpenUrl(Thunderbird.FDROID_URL)
            }
        }

        PageExtras.INSTALL_OPENKEYCHAIN -> ActionRow {
            StepAction(stringResource(MR.string.email_tb_get_play)) {
                actions.onOpenUrl(Thunderbird.OPENKEYCHAIN_PLAY_URL)
            }
            StepAction(stringResource(MR.string.email_tb_get_fdroid)) {
                actions.onOpenUrl(Thunderbird.OPENKEYCHAIN_FDROID_URL)
            }
        }

        PageExtras.ADDRESS -> actions.address?.let { address ->
            ActionRow {
                StepAction(stringResource(MR.string.email_tb_copy_address)) { actions.onCopy(address) }
            }
        }

        // The password page doubles as the manual-settings fallback: autoconfig answers for this
        // server, so the hosts and ports are only needed when it does not.
        PageExtras.PASSWORD -> {
            // Password only: the address went in on the page before, and two copy buttons side by
            // side left people guessing which one the Password field wanted.
            ActionRow {
                actions.password?.let { password ->
                    StepAction(stringResource(MR.string.email_tb_copy_password)) { actions.onCopy(password) }
                }
            }
            actions.settings?.let { settings ->
                Body(stringResource(MR.string.email_tb_manual_note))
                Spacer(modifier = Modifier.height(8.dp))
                MailSettingsCard(
                    title = stringResource(MR.string.email_settings_incoming),
                    host = settings.incomingHost,
                    port = settings.incomingPort,
                    security = settings.incomingSocketType,
                    username = settings.username,
                    onCopy = actions.onCopy,
                )
                Spacer(modifier = Modifier.height(8.dp))
                MailSettingsCard(
                    title = stringResource(MR.string.email_settings_outgoing),
                    host = settings.outgoingHost,
                    port = settings.outgoingPort,
                    security = settings.outgoingSocketType,
                    username = settings.username,
                    onCopy = actions.onCopy,
                )
            }
        }

        PageExtras.SAVE_KEY -> actions.onSaveKey?.let { save ->
            ActionRow {
                StepAction(stringResource(MR.string.email_secrets_save_private_key), save)
            }
        }

        PageExtras.PUBLISH -> ActionRow {
            actions.onCopyPublicKey?.let { copyPublic ->
                StepAction(stringResource(MR.string.email_tb_copy_public_key), copyPublic)
            }
            StepAction(stringResource(MR.string.email_tb_open_keyserver)) {
                actions.onOpenUrl(Thunderbird.KEYSERVER_UPLOAD_URL)
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Spacer(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
            )
        }
    }
}

private enum class PageExtras {
    NONE,
    INSTALL_THUNDERBIRD,
    INSTALL_OPENKEYCHAIN,
    ADDRESS,
    PASSWORD,
    SAVE_KEY,
    PUBLISH,
}

private data class StoryboardPage(
    val title: StringResource,
    val body: StringResource,
    val image: DrawableResource,
    val extras: PageExtras,
    /** Background the user can skip and still finish the step. Rendered after [body]. */
    val notes: List<StringResource> = emptyList(),
)

private val storyboardPages: List<StoryboardPage> = listOf(
    StoryboardPage(
        MR.string.email_tb_sb_install_title,
        MR.string.email_tb_sb_install_body,
        MR.drawable.tb_sb_install,
        PageExtras.INSTALL_THUNDERBIRD,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_account_title,
        MR.string.email_tb_sb_account_body,
        MR.drawable.tb_sb_account,
        PageExtras.NONE,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_address_title,
        MR.string.email_tb_sb_address_body,
        MR.drawable.tb_sb_address,
        PageExtras.ADDRESS,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_config_title,
        MR.string.email_tb_sb_config_body,
        MR.drawable.tb_sb_config,
        PageExtras.PASSWORD,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_folders_title,
        MR.string.email_tb_sb_folders_body,
        MR.drawable.tb_sb_folders,
        PageExtras.NONE,
    ),
    // The test message is the whole point of the three pages that follow: without one sent
    // before OpenPGP exists, "your mail arrives but stays locked" has nothing to show.
    StoryboardPage(
        MR.string.email_tb_sb_test_title,
        MR.string.email_tb_sb_test_body,
        MR.drawable.tb_sb_test,
        PageExtras.ADDRESS,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_encrypted_title,
        MR.string.email_tb_sb_encrypted_body,
        MR.drawable.tb_sb_encrypted,
        PageExtras.NONE,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_okc_title,
        MR.string.email_tb_sb_okc_body,
        MR.drawable.tb_sb_okc,
        PageExtras.INSTALL_OPENKEYCHAIN,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_savekey_title,
        MR.string.email_tb_sb_savekey_body,
        MR.drawable.tb_sb_savekey,
        PageExtras.SAVE_KEY,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_importstart_title,
        MR.string.email_tb_sb_importstart_body,
        MR.drawable.tb_sb_importstart,
        PageExtras.NONE,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_pick_title,
        MR.string.email_tb_sb_pick_body,
        MR.drawable.tb_sb_pick,
        PageExtras.NONE,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_import_title,
        MR.string.email_tb_sb_import_body,
        MR.drawable.tb_sb_import,
        PageExtras.NONE,
    ),
    StoryboardPage(
        MR.string.email_tb_sb_allow_title,
        MR.string.email_tb_sb_allow_body,
        MR.drawable.tb_sb_allow,
        PageExtras.NONE,
    ),
    // Success before housekeeping: the reward for eleven pages of setup should not be an
    // instruction to go delete something.
    StoryboardPage(
        MR.string.email_tb_sb_done_title,
        MR.string.email_tb_sb_done_body,
        MR.drawable.tb_sb_done,
        PageExtras.PUBLISH,
        notes = listOf(MR.string.email_tb_sb_done_note, MR.string.email_tb_sb_done_keyserver),
    ),
    // The same folder shot as the pick page: it is the same file, in the same place, and this is
    // the page that says to get rid of it.
    StoryboardPage(
        MR.string.email_tb_sb_delete_title,
        MR.string.email_tb_sb_delete_body,
        MR.drawable.tb_sb_pick,
        PageExtras.NONE,
    ),
)
