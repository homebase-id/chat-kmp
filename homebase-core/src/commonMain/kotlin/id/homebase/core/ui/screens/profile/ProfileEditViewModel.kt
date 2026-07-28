@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

/**
 * Drives the owner's standard-profile editor.
 *
 * Every attribute type has up to two independent [ProfileAttribute] records — one at
 * [ProfileVisibility.ANONYMOUS] (visible to everyone), one at [ProfileVisibility.CONNECTED]
 * (visible only to connections) — since ACL enforcement is per-record on the server, not per-key
 * within a record. [loadedAnonymous]/[loadedConnected] track each bucket's currently-known record;
 * legacy [ProfileVisibility.AUTHENTICATED]/[ProfileVisibility.OWNER] data loads into the Connected
 * bucket for editing (closest available tab) but is left untouched on the server unless the user
 * actually edits that tab's content — see [computeAttributeEdit].
 *
 * Load: reads every standard-profile attribute from the ProfileDrive and buckets each type's
 * fields into [ProfileEditUiState.anonymousValues]/[ProfileEditUiState.connectedValues].
 * Save: there's no screen-wide Save — [saveAttribute] persists one (type, tier) at a time, fired
 * per-row by the Screen. It rebuilds that record's `data` object (merging the edited keys over the
 * keys we read so unmodelled fields survive) and, only if it actually changed, writes it via
 * [ProfileRepository.save] (which round-trips the versionTag and re-reads on a 409).
 *
 * v1 simplification: a single attribute per (type, tier). If multiple of one type/tier exist on
 * the drive, the first is edited and the rest are left untouched.
 */
class ProfileEditViewModel(
    private val repository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileEditEvent> = _events.asSharedFlow()

    /** The Anonymous-tier attribute per type, as last read/written — source of id/versionTag. */
    private var loadedAnonymous: Map<String, ProfileAttribute> = emptyMap()

    /** The Connected-tier attribute per type, as last read/written (may be a legacy
     *  Authenticated/Owner record — see class doc). */
    private var loadedConnected: Map<String, ProfileAttribute> = emptyMap()

    init {
        load()
    }

    fun onAction(action: ProfileEditAction) {
        when (action) {
            is ProfileEditAction.FieldChanged -> updateField(action.field, action.tier, action.value)
            is ProfileEditAction.SaveAttribute -> saveAttribute(action.type, action.tier)
            ProfileEditAction.RetryLoadClicked -> load()
            ProfileEditAction.BackClicked -> _events.tryEmit(ProfileEditEvent.Back)
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val attributes = try {
                repository.loadAttributes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to load profile attributes" }
                _state.update { it.copy(isLoading = false, loadFailed = true) }
                return@launch
            }

            val byType = attributes.groupBy { it.type }
            loadedAnonymous = byType.mapNotNull { (type, attrs) ->
                attrs.firstOrNull { it.visibility == ProfileVisibility.ANONYMOUS }?.let { type to it }
            }.toMap()
            loadedConnected = byType.mapNotNull { (type, attrs) ->
                attrs.firstOrNull { it.visibility != ProfileVisibility.ANONYMOUS }?.let { type to it }
            }.toMap()

            // The same query already returns PHOTO-type attributes (managed by the dedicated avatar
            // editor) — just pick them out for ProfilePreview rather than issuing a second fetch.
            val photos = attributes.filter { it.type == ProfileAttributeTypes.PHOTO }
            val anonymousPhoto = photos.firstOrNull { it.visibility == ProfileVisibility.ANONYMOUS }
            val connectedPhoto = photos.firstOrNull { it.visibility == ProfileVisibility.CONNECTED }

            _state.update {
                applyLoaded(it).copy(anonymousPhoto = anonymousPhoto, connectedPhoto = connectedPhoto)
            }
        }
    }

    /** Overlays each bucket's loaded attribute values onto a fresh form. */
    private fun applyLoaded(base: ProfileEditUiState): ProfileEditUiState {
        fun bucket(loaded: Map<String, ProfileAttribute>): Map<ProfileField, String> =
            TYPE_FIELDS.flatMap { (type, fields) ->
                fields.map { (field, key) -> field to loaded[type]?.string(key).orEmpty() }
            }.toMap()

        return base.copy(
            isLoading = false,
            loadFailed = false,
            anonymousValues = bucket(loadedAnonymous),
            connectedValues = bucket(loadedConnected),
        )
    }

    private fun updateField(field: ProfileField, tier: ProfileVisibility, value: String) {
        _state.update {
            if (tier == ProfileVisibility.ANONYMOUS) it.copy(anonymousValues = it.anonymousValues + (field to value))
            else it.copy(connectedValues = it.connectedValues + (field to value))
        }
    }

    /**
     * Persists just one attribute type's [tier] record — the unit of work a row's checkmark
     * triggers. [ProfileEditUiState.savingAttributes] tracks the in-flight (type, tier) pair so the
     * row can show a spinner; [ProfileEditEvent.AttributeSaved] tells the Screen to collapse that
     * row back to its read-only display once the save (or no-op) completes.
     */
    private fun saveAttribute(type: String, tier: ProfileVisibility) {
        val key = type to tier
        val edit = attributeEditFor(_state.value, loadedAnonymous, loadedConnected, type, tier)
        if (edit == null) {
            // Nothing actually changed vs. what's persisted — just close the row.
            _events.tryEmit(ProfileEditEvent.AttributeSaved(type, tier))
            return
        }

        _state.update { it.copy(savingAttributes = it.savingAttributes + key) }
        viewModelScope.launch {
            try {
                val existing = if (tier == ProfileVisibility.ANONYMOUS) loadedAnonymous[type] else loadedConnected[type]
                val response = repository.save(
                    type = edit.type,
                    data = edit.data,
                    visibility = edit.visibility,
                    knownId = existing?.id,
                    knownVersionTag = existing?.versionTag,
                )
                // Remember the new id/versionTag so a follow-up save in the same session edits
                // (not re-creates) this attribute.
                val newAttr = ProfileAttribute(
                    id = response.id,
                    type = edit.type,
                    versionTag = response.versionTag,
                    visibility = edit.visibility,
                    data = edit.data,
                )
                if (tier == ProfileVisibility.ANONYMOUS) {
                    loadedAnonymous = loadedAnonymous + (type to newAttr)
                } else {
                    loadedConnected = loadedConnected + (type to newAttr)
                }
                _state.update { it.copy(savingAttributes = it.savingAttributes - key) }
                _events.tryEmit(ProfileEditEvent.AttributeSaved(type, tier))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                _state.update { it.copy(savingAttributes = it.savingAttributes - key) }
                _events.tryEmit(ProfileEditEvent.Forbidden)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to save profile attribute $type ($tier)" }
                _state.update { it.copy(savingAttributes = it.savingAttributes - key) }
                _events.tryEmit(ProfileEditEvent.Error)
            }
        }
    }

    companion object {
        /**
         * The one source of truth for which [ProfileField]s make up each [ProfileAttributeTypes]
         * type and which data key each maps to — shared by [applyLoaded] and [attributeEditFor].
         */
        internal val TYPE_FIELDS: Map<String, List<Pair<ProfileField, String>>> = mapOf(
            ProfileAttributeTypes.NAME to listOf(
                ProfileField.GIVEN_NAME to ProfileAttributeTypes.KEY_GIVEN_NAME,
                ProfileField.SURNAME to ProfileAttributeTypes.KEY_SURNAME,
                ProfileField.ADDITIONAL_NAME to ProfileAttributeTypes.KEY_ADDITIONAL_NAME,
            ),
            ProfileAttributeTypes.NICKNAME to listOf(
                ProfileField.NICKNAME to ProfileAttributeTypes.KEY_NICKNAME,
            ),
            ProfileAttributeTypes.STATUS to listOf(
                ProfileField.STATUS to ProfileAttributeTypes.KEY_STATUS,
            ),
            ProfileAttributeTypes.BIRTHDAY to listOf(
                ProfileField.BIRTHDAY to ProfileAttributeTypes.KEY_BIRTHDAY,
            ),
            ProfileAttributeTypes.EMAIL to listOf(
                ProfileField.EMAIL to ProfileAttributeTypes.KEY_EMAIL,
                ProfileField.EMAIL_LABEL to ProfileAttributeTypes.KEY_LABEL,
            ),
            ProfileAttributeTypes.PHONE to listOf(
                ProfileField.PHONE to ProfileAttributeTypes.KEY_PHONE,
                ProfileField.PHONE_LABEL to ProfileAttributeTypes.KEY_LABEL,
            ),
            ProfileAttributeTypes.ADDRESS to listOf(
                ProfileField.ADDRESS_LABEL to ProfileAttributeTypes.KEY_LABEL,
                ProfileField.ADDRESS1 to ProfileAttributeTypes.KEY_ADDRESS1,
                ProfileField.ADDRESS2 to ProfileAttributeTypes.KEY_ADDRESS2,
                ProfileField.POSTCODE to ProfileAttributeTypes.KEY_POSTCODE,
                ProfileField.CITY to ProfileAttributeTypes.KEY_CITY,
                ProfileField.COUNTRY to ProfileAttributeTypes.KEY_COUNTRY,
            ),
            ProfileAttributeTypes.TWITTER to listOf(
                ProfileField.TWITTER to ProfileAttributeTypes.KEY_TWITTER,
            ),
            ProfileAttributeTypes.FACEBOOK to listOf(
                ProfileField.FACEBOOK to ProfileAttributeTypes.KEY_FACEBOOK,
            ),
            ProfileAttributeTypes.INSTAGRAM to listOf(
                ProfileField.INSTAGRAM to ProfileAttributeTypes.KEY_INSTAGRAM,
            ),
            ProfileAttributeTypes.TIKTOK to listOf(
                ProfileField.TIKTOK to ProfileAttributeTypes.KEY_TIKTOK,
            ),
            ProfileAttributeTypes.LINKEDIN to listOf(
                ProfileField.LINKEDIN to ProfileAttributeTypes.KEY_LINKEDIN,
            ),
        )

        /** One attribute that actually changed, with its full replacement `data` and the tier's
         *  fixed visibility (Anonymous bucket is always ANONYMOUS; Connected bucket is always
         *  CONNECTED — see [computeAttributeEdit]). */
        internal data class AttributeEdit(
            val type: String,
            val data: JsonObject,
            val visibility: ProfileVisibility,
        )

        /**
         * Pure, dependency-free computation of whether [type]'s [tier] bucket needs saving given
         * [updates]. Returns null when nothing changed vs. [existing] — critically, when nothing
         * changed we never even compute a visibility to send, so a legacy Authenticated/Owner
         * attribute loaded into the Connected bucket keeps its real stored visibility on the server
         * for as long as the user leaves that tab untouched. The moment the user *does* edit the
         * Connected tab's content, the record becomes genuinely [ProfileVisibility.CONNECTED] —
         * matching exactly what [ProfilePreview] shows the owner, so Preview never promises
         * visibility the server isn't actually enforcing.
         */
        internal fun computeAttributeEdit(
            existing: ProfileAttribute?,
            type: String,
            updates: Map<String, String>,
            tier: ProfileVisibility,
        ): AttributeEdit? {
            val merged = mergeData(existing?.data, updates)

            if (existing == null && merged.isEmpty()) return null
            if (existing != null && merged == existing.data) return null

            // Real change. For the Name attribute, drop the server-derived displayName so it gets
            // recomputed from the (just-changed) name parts; a deliberate explicitDisplayName override,
            // if present, is left untouched. (Comparison above used the full merged object, so an
            // unchanged Name still correctly skips.)
            val toSend = if (type == ProfileAttributeTypes.NAME) {
                JsonObject(merged - ProfileAttributeTypes.KEY_DISPLAY_NAME)
            } else {
                merged
            }
            val visibility = if (tier == ProfileVisibility.ANONYMOUS) {
                ProfileVisibility.ANONYMOUS
            } else {
                ProfileVisibility.CONNECTED
            }
            return AttributeEdit(type, toSend, visibility)
        }

        /**
         * The (type, tier) attribute's edit vs. what's loaded, or null if nothing changed — the same
         * per-attribute computation both [saveAttribute] (production, one pair at a time) and
         * [pendingEdits] (tests, all pairs at once) run, so they can never drift apart.
         */
        internal fun attributeEditFor(
            s: ProfileEditUiState,
            loadedAnonymous: Map<String, ProfileAttribute>,
            loadedConnected: Map<String, ProfileAttribute>,
            type: String,
            tier: ProfileVisibility,
        ): AttributeEdit? {
            val existing = if (tier == ProfileVisibility.ANONYMOUS) loadedAnonymous[type] else loadedConnected[type]
            val updates = TYPE_FIELDS[type].orEmpty().associate { (field, key) -> key to s.value(field, tier) }
            return computeAttributeEdit(existing, type, updates, tier)
        }

        /**
         * Builds the list of (type, tier) attributes whose data actually changed vs. what's loaded —
         * up to two per type (Anonymous and Connected are independent). Pure and dependency-free
         * (like [computeAttributeEdit]) so the "an untouched legacy attribute's visibility never
         * changes" guarantee is directly unit-testable without a live [ProfileRepository].
         */
        internal fun pendingEdits(
            s: ProfileEditUiState,
            loadedAnonymous: Map<String, ProfileAttribute>,
            loadedConnected: Map<String, ProfileAttribute>,
        ): List<AttributeEdit> = TYPE_FIELDS.keys.flatMap { type ->
            listOfNotNull(
                attributeEditFor(s, loadedAnonymous, loadedConnected, type, ProfileVisibility.ANONYMOUS),
                attributeEditFor(s, loadedAnonymous, loadedConnected, type, ProfileVisibility.CONNECTED),
            )
        }

        private fun mergeData(existing: JsonObject?, updates: Map<String, String>): JsonObject {
            val map = LinkedHashMap<String, JsonElement>()
            existing?.let { map.putAll(it) }
            for ((key, raw) in updates) {
                val value = raw.trim()
                if (value.isEmpty()) map.remove(key) else map[key] = JsonPrimitive(value)
            }
            return JsonObject(map)
        }
    }
}
