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
 * Load: reads every standard-profile attribute from the ProfileDrive and prefills the flat form.
 * Save: for each attribute group, rebuilds its `data` object (merging the edited keys over the keys
 * we read so unmodelled fields survive), then writes only the attributes that actually changed via
 * [ProfileRepository.save] (which round-trips the versionTag and re-reads on a 409).
 *
 * v1 simplification: a single email / phone / address. If multiple of one type exist on the drive,
 * the first is edited and the rest are left untouched.
 */
class ProfileEditViewModel(
    private val repository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileEditEvent> = _events.asSharedFlow()

    /** The attributes as last read/written, keyed by type — the source of id/versionTag/visibility. */
    private var loaded: Map<String, ProfileAttribute> = emptyMap()

    init {
        load()
    }

    fun onAction(action: ProfileEditAction) {
        when (action) {
            is ProfileEditAction.FieldChanged -> updateField(action.field, action.value)
            ProfileEditAction.SaveClicked -> save()
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

            // Keep the first attribute of each type (v1 single email/phone/address).
            loaded = attributes.groupBy { it.type }.mapValues { it.value.first() }
            _state.update { applyLoaded(it) }
        }
    }

    /** Overlays the loaded attribute values onto a fresh form. */
    private fun applyLoaded(base: ProfileEditUiState): ProfileEditUiState {
        fun v(type: String, key: String): String =
            loaded[type]?.string(key).orEmpty()

        return base.copy(
            isLoading = false,
            loadFailed = false,
            givenName = v(ProfileAttributeTypes.NAME, ProfileAttributeTypes.KEY_GIVEN_NAME),
            surname = v(ProfileAttributeTypes.NAME, ProfileAttributeTypes.KEY_SURNAME),
            additionalName = v(ProfileAttributeTypes.NAME, ProfileAttributeTypes.KEY_ADDITIONAL_NAME),
            nickName = v(ProfileAttributeTypes.NICKNAME, ProfileAttributeTypes.KEY_NICKNAME),
            status = v(ProfileAttributeTypes.STATUS, ProfileAttributeTypes.KEY_STATUS),
            birthday = v(ProfileAttributeTypes.BIRTHDAY, ProfileAttributeTypes.KEY_BIRTHDAY),
            email = v(ProfileAttributeTypes.EMAIL, ProfileAttributeTypes.KEY_EMAIL),
            emailLabel = v(ProfileAttributeTypes.EMAIL, ProfileAttributeTypes.KEY_LABEL),
            phone = v(ProfileAttributeTypes.PHONE, ProfileAttributeTypes.KEY_PHONE),
            phoneLabel = v(ProfileAttributeTypes.PHONE, ProfileAttributeTypes.KEY_LABEL),
            addressLabel = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_LABEL),
            address1 = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_ADDRESS1),
            address2 = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_ADDRESS2),
            postcode = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_POSTCODE),
            city = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_CITY),
            country = v(ProfileAttributeTypes.ADDRESS, ProfileAttributeTypes.KEY_COUNTRY),
            twitter = v(ProfileAttributeTypes.TWITTER, ProfileAttributeTypes.KEY_TWITTER),
            facebook = v(ProfileAttributeTypes.FACEBOOK, ProfileAttributeTypes.KEY_FACEBOOK),
            instagram = v(ProfileAttributeTypes.INSTAGRAM, ProfileAttributeTypes.KEY_INSTAGRAM),
            tiktok = v(ProfileAttributeTypes.TIKTOK, ProfileAttributeTypes.KEY_TIKTOK),
            linkedin = v(ProfileAttributeTypes.LINKEDIN, ProfileAttributeTypes.KEY_LINKEDIN),
        )
    }

    private fun updateField(field: ProfileField, value: String) {
        _state.update {
            when (field) {
                ProfileField.GIVEN_NAME -> it.copy(givenName = value)
                ProfileField.SURNAME -> it.copy(surname = value)
                ProfileField.ADDITIONAL_NAME -> it.copy(additionalName = value)
                ProfileField.NICKNAME -> it.copy(nickName = value)
                ProfileField.STATUS -> it.copy(status = value)
                ProfileField.BIRTHDAY -> it.copy(birthday = value)
                ProfileField.EMAIL -> it.copy(email = value)
                ProfileField.EMAIL_LABEL -> it.copy(emailLabel = value)
                ProfileField.PHONE -> it.copy(phone = value)
                ProfileField.PHONE_LABEL -> it.copy(phoneLabel = value)
                ProfileField.ADDRESS_LABEL -> it.copy(addressLabel = value)
                ProfileField.ADDRESS1 -> it.copy(address1 = value)
                ProfileField.ADDRESS2 -> it.copy(address2 = value)
                ProfileField.POSTCODE -> it.copy(postcode = value)
                ProfileField.CITY -> it.copy(city = value)
                ProfileField.COUNTRY -> it.copy(country = value)
                ProfileField.TWITTER -> it.copy(twitter = value)
                ProfileField.FACEBOOK -> it.copy(facebook = value)
                ProfileField.INSTAGRAM -> it.copy(instagram = value)
                ProfileField.TIKTOK -> it.copy(tiktok = value)
                ProfileField.LINKEDIN -> it.copy(linkedin = value)
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val edits = pendingEdits(s)
            if (edits.isEmpty()) {
                _state.update { it.copy(isSaving = false) }
                _events.tryEmit(ProfileEditEvent.Saved)
                return@launch
            }

            var forbidden = false
            var failed = false
            for (edit in edits) {
                try {
                    val existing = loaded[edit.type]
                    val response = repository.save(
                        type = edit.type,
                        data = edit.data,
                        visibility = existing?.visibility
                            ?: ProfileAttributeTypes.defaultVisibilityFor(edit.type),
                        knownId = existing?.id,
                        knownVersionTag = existing?.versionTag,
                    )
                    // Remember the new id/versionTag so a follow-up save in the same session edits
                    // (not re-creates) this attribute.
                    loaded = loaded + (edit.type to ProfileAttribute(
                        id = response.id,
                        type = edit.type,
                        versionTag = response.versionTag,
                        visibility = existing?.visibility
                            ?: ProfileAttributeTypes.defaultVisibilityFor(edit.type),
                        data = edit.data,
                    ))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ForbiddenException) {
                    forbidden = true
                    break
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to save profile attribute ${edit.type}" }
                    failed = true
                }
            }

            _state.update { it.copy(isSaving = false) }
            when {
                forbidden -> _events.tryEmit(ProfileEditEvent.Forbidden)
                failed -> _events.tryEmit(ProfileEditEvent.Error)
                else -> _events.tryEmit(ProfileEditEvent.Saved)
            }
        }
    }

    /** One attribute that actually changed, with its full replacement `data`. */
    private data class AttributeEdit(val type: String, val data: JsonObject)

    /**
     * Builds the list of attributes whose data differs from what we loaded. Each attribute's new data
     * merges the edited keys over the existing object (so unmodelled keys survive); a brand-new
     * attribute whose fields are all blank is skipped rather than created empty.
     */
    private fun pendingEdits(s: ProfileEditUiState): List<AttributeEdit> {
        val candidates = listOf(
            edit(ProfileAttributeTypes.NAME, mapOf(
                ProfileAttributeTypes.KEY_GIVEN_NAME to s.givenName,
                ProfileAttributeTypes.KEY_SURNAME to s.surname,
                ProfileAttributeTypes.KEY_ADDITIONAL_NAME to s.additionalName,
            )),
            edit(ProfileAttributeTypes.NICKNAME, mapOf(
                ProfileAttributeTypes.KEY_NICKNAME to s.nickName,
            )),
            edit(ProfileAttributeTypes.STATUS, mapOf(
                ProfileAttributeTypes.KEY_STATUS to s.status,
            )),
            edit(ProfileAttributeTypes.BIRTHDAY, mapOf(
                ProfileAttributeTypes.KEY_BIRTHDAY to s.birthday,
            )),
            edit(ProfileAttributeTypes.EMAIL, mapOf(
                ProfileAttributeTypes.KEY_EMAIL to s.email,
                ProfileAttributeTypes.KEY_LABEL to s.emailLabel,
            )),
            edit(ProfileAttributeTypes.PHONE, mapOf(
                ProfileAttributeTypes.KEY_PHONE to s.phone,
                ProfileAttributeTypes.KEY_LABEL to s.phoneLabel,
            )),
            edit(ProfileAttributeTypes.ADDRESS, mapOf(
                ProfileAttributeTypes.KEY_LABEL to s.addressLabel,
                ProfileAttributeTypes.KEY_ADDRESS1 to s.address1,
                ProfileAttributeTypes.KEY_ADDRESS2 to s.address2,
                ProfileAttributeTypes.KEY_POSTCODE to s.postcode,
                ProfileAttributeTypes.KEY_CITY to s.city,
                ProfileAttributeTypes.KEY_COUNTRY to s.country,
            )),
            edit(ProfileAttributeTypes.TWITTER, mapOf(ProfileAttributeTypes.KEY_TWITTER to s.twitter)),
            edit(ProfileAttributeTypes.FACEBOOK, mapOf(ProfileAttributeTypes.KEY_FACEBOOK to s.facebook)),
            edit(ProfileAttributeTypes.INSTAGRAM, mapOf(ProfileAttributeTypes.KEY_INSTAGRAM to s.instagram)),
            edit(ProfileAttributeTypes.TIKTOK, mapOf(ProfileAttributeTypes.KEY_TIKTOK to s.tiktok)),
            edit(ProfileAttributeTypes.LINKEDIN, mapOf(ProfileAttributeTypes.KEY_LINKEDIN to s.linkedin)),
        )
        return candidates.filterNotNull()
    }

    /**
     * Produces an [AttributeEdit] for [type] if its merged data differs from what was loaded, else
     * null. Blank values remove their key; a new attribute that ends up empty is dropped.
     */
    private fun edit(type: String, updates: Map<String, String>): AttributeEdit? {
        val existing = loaded[type]
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
        return AttributeEdit(type, toSend)
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
