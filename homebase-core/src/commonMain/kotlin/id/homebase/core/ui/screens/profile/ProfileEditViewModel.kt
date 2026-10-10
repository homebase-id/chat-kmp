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
import id.homebase.api.util.compareStringUuId
import id.homebase.core.ui.screens.card.CardCircle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlin.uuid.Uuid

/**
 * Drives the owner's standard-profile editor. Each attribute type is one [ProfileAttribute] whose
 * ACL is the [ProfileAudience] the user picked: Public, a set of Contacts-app circles, or Only me.
 *
 * Load reads every standard-profile attribute and the Contacts circles. If a type has several
 * records (the old two-tier editor wrote a Public and a Connected one), the Public one is edited.
 * A leftover holding the same data is deleted once an edit is saved, so exactly one record decides
 * the audience. A leftover holding different data is never deleted silently: it is shown in the
 * row ([ProfileEditUiState.conflicts]) and goes only when the user discards it. Links are the one
 * type with many records, each its own row ([LinkDraft]).
 *
 * Save: there's no screen-wide Save — [saveAttribute] persists one type at a time. It rebuilds
 * that record's `data` (merging the edited keys over the keys we read so unmodelled fields
 * survive) and writes it with the chosen visibility and `circleIds` via [ProfileRepository.save],
 * only if the data or audience actually changed. A legacy Connected record with no circles reads as
 * every circle and is only rewritten, with an explicit circle list, once the user changes it.
 */
class ProfileEditViewModel(
    private val repository: ProfileRepository,
    /** Every circle's name by id; read only when a detail is shared with circles that have no card. */
    private val loadCircleNames: suspend () -> Map<String, String> = { emptyMap() },
    private val loadCircles: suspend () -> List<CardCircle>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileEditUiState(),
    )
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileEditEvent> = _events.asSharedFlow()

    /** The edited record per type, as last read/written — source of id/versionTag. */
    private var loaded: Map<String, ProfileAttribute> = emptyMap()

    /** Each loaded record's audience, to tell a real audience change from an untouched legacy one. */
    private var loadedAudiences: Map<String, ProfileAudience> = emptyMap()

    /** Leftovers holding the same data as the edited record; the next saved edit of that type deletes them. */
    private var duplicates: Map<String, List<ProfileAttribute>> = emptyMap()

    private var loadedLinks: Map<String, ProfileAttribute> = emptyMap()
    private var loadedLinkAudiences: Map<String, ProfileAudience> = emptyMap()
    private var newLinkCounter = 0

    init {
        load()
    }

    fun onAction(action: ProfileEditAction) {
        when (action) {
            is ProfileEditAction.FieldChanged -> _state.update { it.copy(values = it.values + (action.field to action.value)) }
            is ProfileEditAction.AudienceChanged -> _state.update { it.copy(audiences = it.audiences + (action.type to action.audience)) }
            is ProfileEditAction.SaveAttribute -> saveAttribute(action.type)
            ProfileEditAction.AddLink -> _state.update { it.copy(links = it.links + LinkDraft(key = "new-${newLinkCounter++}")) }
            is ProfileEditAction.LinkChanged -> updateLink(action.key) { it.copy(text = action.text, target = action.target) }
            is ProfileEditAction.LinkAudienceChanged -> updateLink(action.key) { it.copy(audience = action.audience) }
            is ProfileEditAction.SaveLink -> saveLink(action.key)
            is ProfileEditAction.RemoveLink -> removeLink(action.key)
            is ProfileEditAction.DiscardConflict -> discardConflict(action.type, action.id)
            ProfileEditAction.RetryLoadClicked -> load()
            ProfileEditAction.BackClicked -> _events.tryEmit(ProfileEditEvent.Back)
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val (attributes, circles) = try {
                coroutineScope {
                    val circles = async { loadCircles() }
                    repository.loadAttributes() to circles.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to load profile attributes" }
                _state.update { it.copy(isLoading = false, loadFailed = true) }
                return@launch
            }

            val result = LoadedProfileAttributes.from(attributes)
            loaded = result.byType
            duplicates = result.duplicates
            loadedLinks = result.links.associateBy { it.id.toString() }
            // Set before isLoading flips: a save that lands first would diff against nothing and rewrite.
            loadedAudiences = result.byType.mapValues { (_, attribute) -> attribute.audience(circles) }
            loadedLinkAudiences = result.links.associate { it.id.toString() to it.audience(circles) }
            _state.update { it.withLoaded(result, circles) }
            nameOtherCircles()
        }
    }

    private suspend fun nameOtherCircles() {
        val s = _state.value
        val others = (s.audiences.values + s.links.map { it.audience })
            .flatMap { (it as? ProfileAudience.Circles)?.otherIds.orEmpty() }
            .toSet()
        if (others.isEmpty()) return
        val names = try {
            loadCircleNames()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Failed to load circle names" }
            return
        }
        val named = others.mapNotNull { id ->
            names.entries.firstOrNull { compareStringUuId(it.key, id) }?.let { id to it.value }
        }.toMap()
        _state.update { it.copy(otherCircleNames = named) }
    }

    private fun saveAttribute(type: String) {
        val current = _state.value
        val audience = current.audience(type)
        if (!audience.isSavableWith(current.circles)) {
            _events.tryEmit(ProfileEditEvent.Error)
            return
        }
        val existing = loaded[type]
        val edit = attributeEditFor(current, existing, loadedAudiences[type], type)
        if (edit == null) {
            if (type in cleanupDue && duplicates[type].orEmpty().isNotEmpty()) {
                viewModelScope.launch { finishSave(type) }
            } else {
                _events.tryEmit(ProfileEditEvent.AttributeSaved(type))
            }
            return
        }
        persist(type, edit, existing) { newAttr ->
            loaded = loaded + (type to newAttr)
            loadedAudiences = loadedAudiences + (type to edit.audience)
            cleanupDue += type
            finishSave(type)
        }
    }

    private fun updateLink(key: String, block: (LinkDraft) -> LinkDraft) {
        _state.update { s -> s.copy(links = s.links.map { if (it.key == key) block(it) else it }) }
    }

    private fun saveLink(key: String) {
        val draft = _state.value.links.firstOrNull { it.key == key } ?: return
        if (!draft.isValid(_state.value.circles)) {
            _events.tryEmit(ProfileEditEvent.Error)
            return
        }
        val existing = loadedLinks[key]
        val updates = mapOf(
            ProfileAttributeTypes.KEY_LINK_TEXT to draft.text,
            ProfileAttributeTypes.KEY_LINK_TARGET to draft.target,
        )
        val edit = computeAttributeEdit(existing, ProfileAttributeTypes.LINK, updates, draft.audience, loadedLinkAudiences[key])
        if (edit == null) {
            _events.tryEmit(ProfileEditEvent.AttributeSaved(key))
            return
        }
        persist(key, edit, existing) { newAttr ->
            val newKey = newAttr.id.toString()
            loadedLinks = loadedLinks - key + (newKey to newAttr)
            loadedLinkAudiences = loadedLinkAudiences - key + (newKey to edit.audience)
            _state.update { s ->
                s.copy(
                    links = s.links.map { if (it.key == key) it.copy(key = newKey) else it },
                    savingAttributes = s.savingAttributes - key,
                )
            }
            _events.tryEmit(ProfileEditEvent.AttributeSaved(key))
        }
    }

    private fun removeLink(key: String) {
        val existing = loadedLinks[key]
        if (existing == null) {
            _state.update { s -> s.copy(links = s.links.filterNot { it.key == key }) }
            return
        }
        _state.update { it.copy(savingAttributes = it.savingAttributes + key) }
        viewModelScope.launch {
            val ok = try {
                repository.delete(existing.id, existing.versionTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to remove profile link" }
                false
            }
            if (!ok) {
                _state.update { it.copy(savingAttributes = it.savingAttributes - key) }
                _events.tryEmit(ProfileEditEvent.Error)
                return@launch
            }
            loadedLinks = loadedLinks - key
            loadedLinkAudiences = loadedLinkAudiences - key
            _state.update { s ->
                s.copy(
                    links = s.links.filterNot { it.key == key },
                    attributes = s.attributes.filterNot { it.id == existing.id },
                    savingAttributes = s.savingAttributes - key,
                )
            }
            _events.tryEmit(ProfileEditEvent.AttributeSaved(key))
        }
    }

    /** Writes [edit], records the stored attribute in state, then runs [onWritten]; failures clear [savingKey] and report. */
    private fun persist(
        savingKey: String,
        edit: AttributeEdit,
        existing: ProfileAttribute?,
        onWritten: suspend (ProfileAttribute) -> Unit,
    ) {
        _state.update { it.copy(savingAttributes = it.savingAttributes + savingKey) }
        viewModelScope.launch {
            try {
                val newAttr = repository.saveWithAudience(edit.type, edit.data, edit.audience, existing)
                _state.update {
                    it.copy(attributes = it.attributes.filterNot { stored -> stored.id == newAttr.id } + newAttr)
                }
                onWritten(newAttr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                _state.update { it.copy(savingAttributes = it.savingAttributes - savingKey) }
                _events.tryEmit(ProfileEditEvent.Forbidden)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to save profile attribute ${edit.type}" }
                _state.update { it.copy(savingAttributes = it.savingAttributes - savingKey) }
                _events.tryEmit(ProfileEditEvent.Error)
            }
        }
    }

    private fun discardConflict(type: String, id: Uuid) {
        val record = _state.value.conflicts[type].orEmpty().firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val ok = try {
                repository.delete(record.id, record.versionTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to remove conflicting profile attribute $type" }
                false
            }
            if (!ok) {
                _events.tryEmit(ProfileEditEvent.Error)
                return@launch
            }
            _state.update { s ->
                s.copy(
                    conflicts = (s.conflicts + (type to s.conflicts[type].orEmpty().filter { it.id != id })).filterValues { it.isNotEmpty() },
                    attributes = s.attributes.filterNot { it.id == id },
                )
            }
        }
    }

    private val cleanupDue = mutableSetOf<String>()

    /** Deletes the same-data leftovers of [type] after its edit was written, then reports the save. */
    private suspend fun finishSave(type: String) {
        val leftovers = duplicates[type].orEmpty()
        val deleted = mutableSetOf<Uuid>()
        var failed = false
        for (attribute in leftovers) {
            val ok = try {
                repository.delete(attribute.id, attribute.versionTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to remove leftover profile attribute $type" }
                false
            }
            if (ok) deleted += attribute.id else failed = true
        }
        duplicates = duplicates + (type to leftovers.filter { it.id !in deleted })
        if (!failed) cleanupDue -= type
        _state.update {
            it.copy(
                savingAttributes = it.savingAttributes - type,
                attributes = it.attributes.filterNot { stored -> stored.id in deleted },
            )
        }
        _events.tryEmit(if (failed) ProfileEditEvent.Error else ProfileEditEvent.AttributeSaved(type))
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
            ProfileAttributeTypes.BIO_SUMMARY to listOf(
                ProfileField.BIO to ProfileAttributeTypes.KEY_SHORT_BIO,
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


/**
 * The record the editor writes per type: the Public one if there is one, else the first other.
 * Leftovers of the same type split by data: [duplicates] equal the edited record's (safe to delete
 * on save) and [conflicts] differ (the user decides). Links are many-per-profile, so they bypass
 * this and come back as [links].
 */
internal class LoadedProfileAttributes(
    val byType: Map<String, ProfileAttribute>,
    val all: List<ProfileAttribute>,
    val duplicates: Map<String, List<ProfileAttribute>>,
    val conflicts: Map<String, List<ProfileAttribute>>,
    val links: List<ProfileAttribute>,
) {
    companion object {
        fun from(attributes: List<ProfileAttribute>): LoadedProfileAttributes {
            val single = attributes.filter { it.type != ProfileAttributeTypes.LINK }
            val byType = single.groupBy { it.type }.mapValues { (_, attrs) ->
                attrs.firstOrNull { it.visibility == ProfileVisibility.ANONYMOUS } ?: attrs.first()
            }
            val leftovers = single.groupBy { it.type }
                .mapValues { (type, attrs) -> attrs.filter { it.id != byType[type]?.id } }
                .filterValues { it.isNotEmpty() }
            return LoadedProfileAttributes(
                byType = byType,
                all = attributes,
                duplicates = leftovers.mapValues { (type, attrs) -> attrs.filter { it.data == byType[type]?.data } }.filterValues { it.isNotEmpty() },
                conflicts = leftovers.mapValues { (type, attrs) -> attrs.filter { it.data != byType[type]?.data } }.filterValues { it.isNotEmpty() },
                links = attributes.filter { it.type == ProfileAttributeTypes.LINK }.sortedBy { it.priority },
            )
        }
    }
}

internal fun ProfileEditUiState.withLoaded(loaded: LoadedProfileAttributes, circles: List<CardCircle>): ProfileEditUiState = copy(
    isLoading = false,
    loadFailed = false,
    values = ProfileEditViewModel.TYPE_FIELDS.flatMap { (type, fields) ->
        fields.map { (field, key) -> field to loaded.byType[type]?.string(key).orEmpty() }
    }.toMap(),
    audiences = loaded.byType.mapValues { (_, attribute) -> attribute.audience(circles) },
    links = loaded.links.map {
        LinkDraft(
            key = it.id.toString(),
            text = it.string(ProfileAttributeTypes.KEY_LINK_TEXT).orEmpty(),
            target = it.string(ProfileAttributeTypes.KEY_LINK_TARGET).orEmpty(),
            audience = it.audience(circles),
        )
    },
    conflicts = loaded.conflicts,
    circles = circles,
    attributes = loaded.all,
)
