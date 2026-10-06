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
import id.homebase.core.ui.screens.card.CardCircle
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
 * Drives the owner's standard-profile editor. Each attribute type is one [ProfileAttribute] whose
 * ACL is the [ProfileAudience] the user picked: Public, a set of Contacts-app circles, or Only me.
 *
 * Load reads every standard-profile attribute and the Contacts circles. If a type has several
 * records (the old two-tier editor wrote a Public and a Connected one), the Public one is edited
 * and the others are left untouched on the server.
 *
 * Save: there's no screen-wide Save — [saveAttribute] persists one type at a time. It rebuilds
 * that record's `data` (merging the edited keys over the keys we read so unmodelled fields
 * survive) and writes it with the chosen visibility and `circleIds` via [ProfileRepository.save],
 * only if the data or audience actually changed. A legacy Connected record with no circles reads as
 * every circle and is only rewritten, with an explicit circle list, once the user changes it.
 */
class ProfileEditViewModel(
    private val repository: ProfileRepository,
    reviewEnabled: Boolean,
    private val loadCircles: suspend () -> List<CardCircle>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileEditUiState(reviewEnabled = reviewEnabled),
    )
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileEditEvent> = _events.asSharedFlow()

    /** The edited record per type, as last read/written — source of id/versionTag. */
    private var loaded: Map<String, ProfileAttribute> = emptyMap()

    /** Each loaded record's audience, to tell a real audience change from an untouched legacy one. */
    private var loadedAudiences: Map<String, ProfileAudience> = emptyMap()

    init {
        load()
    }

    fun onAction(action: ProfileEditAction) {
        when (action) {
            is ProfileEditAction.FieldChanged -> _state.update { it.copy(values = it.values + (action.field to action.value)) }
            is ProfileEditAction.AudienceChanged -> _state.update { it.copy(audiences = it.audiences + (action.type to action.audience)) }
            is ProfileEditAction.SaveAttribute -> saveAttribute(action.type)
            ProfileEditAction.RetryLoadClicked -> load()
            ProfileEditAction.BackClicked -> _events.tryEmit(ProfileEditEvent.Back)
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val (attributes, circles) = try {
                repository.loadAttributes() to loadCircles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to load profile attributes" }
                _state.update { it.copy(isLoading = false, loadFailed = true) }
                return@launch
            }

            val result = LoadedProfileAttributes.from(attributes)
            loaded = result.byType
            loadedAudiences = result.byType.mapValues { (_, attribute) -> attribute.audience(circles) }
            _state.update { it.withLoaded(result, circles) }
        }
    }

    private fun saveAttribute(type: String) {
        val current = _state.value
        val audience = current.audience(type)
        if (!audience.isSavable) {
            _events.tryEmit(ProfileEditEvent.Error)
            return
        }
        val existing = loaded[type]
        val edit = attributeEditFor(current, existing, loadedAudiences[type], type)
        if (edit == null) {
            _events.tryEmit(ProfileEditEvent.AttributeSaved(type))
            return
        }

        _state.update { it.copy(savingAttributes = it.savingAttributes + type) }
        viewModelScope.launch {
            try {
                val response = repository.save(
                    type = edit.type,
                    data = edit.data,
                    visibility = edit.audience.visibility,
                    knownId = existing?.id,
                    knownVersionTag = existing?.versionTag,
                    circleIds = edit.audience.circleIds,
                )
                val newAttr = ProfileAttribute(
                    id = response.id,
                    type = edit.type,
                    versionTag = response.versionTag,
                    visibility = edit.audience.visibility,
                    data = edit.data,
                    acl = edit.audience.toAcl(),
                )
                loaded = loaded + (type to newAttr)
                loadedAudiences = loadedAudiences + (type to edit.audience)
                _state.update {
                    it.copy(
                        savingAttributes = it.savingAttributes - type,
                        attributes = it.attributes.filterNot { stored -> stored.id == newAttr.id } + newAttr,
                    )
                }
                _events.tryEmit(ProfileEditEvent.AttributeSaved(type))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                _state.update { it.copy(savingAttributes = it.savingAttributes - type) }
                _events.tryEmit(ProfileEditEvent.Forbidden)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to save profile attribute $type" }
                _state.update { it.copy(savingAttributes = it.savingAttributes - type) }
                _events.tryEmit(ProfileEditEvent.Error)
            }
        }
    }

    companion object {
        /**
         * The one source of truth for which [ProfileField]s make up each [ProfileAttributeTypes]
         * type and which data key each maps to.
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

        /** One attribute that actually changed, with its full replacement `data` and its audience. */
        internal data class AttributeEdit(
            val type: String,
            val data: JsonObject,
            val audience: ProfileAudience,
        )

        /**
         * Pure computation of whether [type] needs saving given [updates] and [audience]. Null when
         * neither the data nor the audience changed against [existing] / [loadedAudience] —
         * critically, an untouched legacy record keeps its stored visibility on the server.
         */
        internal fun computeAttributeEdit(
            existing: ProfileAttribute?,
            type: String,
            updates: Map<String, String>,
            audience: ProfileAudience,
            loadedAudience: ProfileAudience?,
        ): AttributeEdit? {
            val merged = mergeData(existing?.data, updates)
            val dataChanged = if (existing == null) merged.isNotEmpty() else merged != existing.data
            val audienceChanged = existing != null && audience != loadedAudience
            if (!dataChanged && !audienceChanged) return null

            // Drop the server-derived displayName so it is recomputed from the changed name parts;
            // a deliberate explicitDisplayName override is left untouched.
            val toSend = if (type == ProfileAttributeTypes.NAME && dataChanged) {
                JsonObject(merged - ProfileAttributeTypes.KEY_DISPLAY_NAME)
            } else {
                merged
            }
            return AttributeEdit(type, toSend, audience)
        }

        internal fun attributeEditFor(
            s: ProfileEditUiState,
            existing: ProfileAttribute?,
            loadedAudience: ProfileAudience?,
            type: String,
        ): AttributeEdit? {
            val updates = TYPE_FIELDS[type].orEmpty().associate { (field, key) -> key to s.value(field) }
            return computeAttributeEdit(existing, type, updates, s.audience(type), loadedAudience)
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


/** The record the editor writes per type: the Public one if there is one, else the first other. */
internal class LoadedProfileAttributes(
    val byType: Map<String, ProfileAttribute>,
    val all: List<ProfileAttribute>,
) {
    companion object {
        fun from(attributes: List<ProfileAttribute>): LoadedProfileAttributes = LoadedProfileAttributes(
            byType = attributes.groupBy { it.type }.mapValues { (_, attrs) ->
                attrs.firstOrNull { it.visibility == ProfileVisibility.ANONYMOUS } ?: attrs.first()
            },
            all = attributes,
        )
    }
}

internal fun ProfileEditUiState.withLoaded(loaded: LoadedProfileAttributes, circles: List<CardCircle>): ProfileEditUiState = copy(
    isLoading = false,
    loadFailed = false,
    values = ProfileEditViewModel.TYPE_FIELDS.flatMap { (type, fields) ->
        fields.map { (field, key) -> field to loaded.byType[type]?.string(key).orEmpty() }
    }.toMap(),
    audiences = loaded.byType.mapValues { (_, attribute) -> attribute.audience(circles) },
    circles = circles,
    attributes = loaded.all,
)
