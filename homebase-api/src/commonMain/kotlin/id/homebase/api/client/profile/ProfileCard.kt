package id.homebase.api.client.profile

data class ProfileCard(
    val image: String, // expected format: https://{host}/pub/image
    val givenName: String?,
    val familyName: String?,
    val status: String?,
    val name: String,
    val bio: String,
    val bioSummary: String?,
    val links: List<Link>,
    val email: List<Email>,
    val sameAs: List<SameAs>
)

data class Link(
    val type: String,
    val url: String?
)

data class Email(
    val type: String,
    val email: String?
)

data class SameAs(
    val type: String,
    val url: String?
)