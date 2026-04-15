package id.homebase.chat.services.convo.contact

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class ContactServerFile(
    val odinId: OdinId?,
    val name: ContactName,
    val source: String?, // 'contact' | 'public' | 'user';

    val location: ContactLocation? = null,
    val phone: ContactPhone? = null,
    val email: ContactEmail? = null,
    val birthday: ContactBirthday? = null,
    val image: ContactImage? = null
)