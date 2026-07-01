package id.homebase.core.ui.screens.contactbook.components

/**
 * A country/territory with its E.164 calling code. [flag] is derived from the ISO
 * 3166-1 alpha-2 code as regional-indicator symbols (so no per-country emoji data is
 * stored; platforms without flag glyphs render the two letters, which is fine).
 */
data class Country(val iso: String, val name: String, val dialCode: String) {
    val flag: String get() = iso.uppercase().map { codePointToString(0x1F1E6 + (it - 'A')) }.joinToString("")
}

private fun codePointToString(cp: Int): String {
    if (cp <= 0xFFFF) return cp.toChar().toString()
    val c = cp - 0x10000
    return charArrayOf((0xD800 + (c shr 10)).toChar(), (0xDC00 + (c and 0x3FF)).toChar()).concatToString()
}

/** Finds the country whose dial code is the longest prefix of an E.164 [number] (without '+'). */
fun countryForE164(number: String): Country? {
    val digits = number.removePrefix("+")
    return countries
        .filter { digits.startsWith(it.dialCode) }
        .maxByOrNull { it.dialCode.length }
}

/** Splits a stored E.164 value into (country, nationalDigits); null country if it can't be matched. */
fun splitE164(value: String): Pair<Country?, String> {
    if (!value.startsWith("+")) return null to value.filter { it.isDigit() }
    val country = countryForE164(value)
    val national = if (country != null) value.removePrefix("+").removePrefix(country.dialCode) else ""
    return country to national.filter { it.isDigit() }
}

/**
 * Formats a stored E.164 number for **display** ("+1 (415) 555-0123") using the dial-code table.
 * NANP numbers get the familiar `(area) prefix-line` shape; everything else is grouped generically
 * (`+CC` then national digits in readable chunks). Display-only — the stored value stays E.164.
 *
 * Returns the input unchanged when it can't be parsed (not E.164, or an unknown dial code), so
 * legacy/unrecognized data is still shown rather than mangled.
 */
fun formatPhoneForDisplay(e164: String): String {
    val v = e164.trim()
    val (country, national) = splitE164(v)
    if (!v.startsWith("+") || country == null || national.isEmpty()) return v
    // North American Numbering Plan: +1 (NXX) NXX-XXXX.
    if (country.dialCode == "1" && national.length == 10) {
        return "+1 (${national.substring(0, 3)}) ${national.substring(3, 6)}-${national.substring(6)}"
    }
    return "+${country.dialCode} ${groupNationalDigits(national)}"
}

/** Groups national digits into readable chunks of three, merging a lone trailing digit into the
 *  previous group so we never leave a one-digit orphan (e.g. "12345678" → "123 456 78"). */
private fun groupNationalDigits(digits: String): String {
    if (digits.length <= 4) return digits
    val groups = digits.chunked(3).toMutableList()
    if (groups.size >= 2 && groups.last().length == 1) {
        val orphan = groups.removeAt(groups.lastIndex)
        groups[groups.lastIndex] = groups.last() + orphan
    }
    return groups.joinToString(" ")
}

private fun c(iso: String, name: String, dial: String) = Country(iso, name, dial)

/** ISO 3166-1 territories with ITU calling codes. Sorted by name in the picker. */
val countries: List<Country> = listOf(
    c("AF", "Afghanistan", "93"), c("AL", "Albania", "355"), c("DZ", "Algeria", "213"),
    c("AD", "Andorra", "376"), c("AO", "Angola", "244"), c("AR", "Argentina", "54"),
    c("AM", "Armenia", "374"), c("AU", "Australia", "61"), c("AT", "Austria", "43"),
    c("AZ", "Azerbaijan", "994"), c("BH", "Bahrain", "973"), c("BD", "Bangladesh", "880"),
    c("BB", "Barbados", "1246"), c("BY", "Belarus", "375"), c("BE", "Belgium", "32"),
    c("BZ", "Belize", "501"), c("BJ", "Benin", "229"), c("BT", "Bhutan", "975"),
    c("BO", "Bolivia", "591"), c("BA", "Bosnia and Herzegovina", "387"), c("BW", "Botswana", "267"),
    c("BR", "Brazil", "55"), c("BN", "Brunei", "673"), c("BG", "Bulgaria", "359"),
    c("BF", "Burkina Faso", "226"), c("BI", "Burundi", "257"), c("KH", "Cambodia", "855"),
    c("CM", "Cameroon", "237"), c("CA", "Canada", "1"), c("CV", "Cape Verde", "238"),
    c("CF", "Central African Republic", "236"), c("TD", "Chad", "235"), c("CL", "Chile", "56"),
    c("CN", "China", "86"), c("CO", "Colombia", "57"), c("KM", "Comoros", "269"),
    c("CG", "Congo", "242"), c("CD", "Congo (DRC)", "243"), c("CR", "Costa Rica", "506"),
    c("CI", "Côte d'Ivoire", "225"), c("HR", "Croatia", "385"), c("CU", "Cuba", "53"),
    c("CY", "Cyprus", "357"), c("CZ", "Czechia", "420"), c("DK", "Denmark", "45"),
    c("DJ", "Djibouti", "253"), c("DM", "Dominica", "1767"), c("DO", "Dominican Republic", "1809"),
    c("EC", "Ecuador", "593"), c("EG", "Egypt", "20"), c("SV", "El Salvador", "503"),
    c("GQ", "Equatorial Guinea", "240"), c("ER", "Eritrea", "291"), c("EE", "Estonia", "372"),
    c("SZ", "Eswatini", "268"), c("ET", "Ethiopia", "251"), c("FJ", "Fiji", "679"),
    c("FI", "Finland", "358"), c("FR", "France", "33"), c("GA", "Gabon", "241"),
    c("GM", "Gambia", "220"), c("GE", "Georgia", "995"), c("DE", "Germany", "49"),
    c("GH", "Ghana", "233"), c("GR", "Greece", "30"), c("GD", "Grenada", "1473"),
    c("GT", "Guatemala", "502"), c("GN", "Guinea", "224"), c("GW", "Guinea-Bissau", "245"),
    c("GY", "Guyana", "592"), c("HT", "Haiti", "509"), c("HN", "Honduras", "504"),
    c("HK", "Hong Kong", "852"), c("HU", "Hungary", "36"), c("IS", "Iceland", "354"),
    c("IN", "India", "91"), c("ID", "Indonesia", "62"), c("IR", "Iran", "98"),
    c("IQ", "Iraq", "964"), c("IE", "Ireland", "353"), c("IL", "Israel", "972"),
    c("IT", "Italy", "39"), c("JM", "Jamaica", "1876"), c("JP", "Japan", "81"),
    c("JO", "Jordan", "962"), c("KZ", "Kazakhstan", "7"), c("KE", "Kenya", "254"),
    c("KI", "Kiribati", "686"), c("KW", "Kuwait", "965"), c("KG", "Kyrgyzstan", "996"),
    c("LA", "Laos", "856"), c("LV", "Latvia", "371"), c("LB", "Lebanon", "961"),
    c("LS", "Lesotho", "266"), c("LR", "Liberia", "231"), c("LY", "Libya", "218"),
    c("LI", "Liechtenstein", "423"), c("LT", "Lithuania", "370"), c("LU", "Luxembourg", "352"),
    c("MO", "Macau", "853"), c("MG", "Madagascar", "261"), c("MW", "Malawi", "265"),
    c("MY", "Malaysia", "60"), c("MV", "Maldives", "960"), c("ML", "Mali", "223"),
    c("MT", "Malta", "356"), c("MR", "Mauritania", "222"), c("MU", "Mauritius", "230"),
    c("MX", "Mexico", "52"), c("MD", "Moldova", "373"), c("MC", "Monaco", "377"),
    c("MN", "Mongolia", "976"), c("ME", "Montenegro", "382"), c("MA", "Morocco", "212"),
    c("MZ", "Mozambique", "258"), c("MM", "Myanmar", "95"), c("NA", "Namibia", "264"),
    c("NP", "Nepal", "977"), c("NL", "Netherlands", "31"), c("NZ", "New Zealand", "64"),
    c("NI", "Nicaragua", "505"), c("NE", "Niger", "227"), c("NG", "Nigeria", "234"),
    c("KP", "North Korea", "850"), c("MK", "North Macedonia", "389"), c("NO", "Norway", "47"),
    c("OM", "Oman", "968"), c("PK", "Pakistan", "92"), c("PW", "Palau", "680"),
    c("PS", "Palestine", "970"), c("PA", "Panama", "507"), c("PG", "Papua New Guinea", "675"),
    c("PY", "Paraguay", "595"), c("PE", "Peru", "51"), c("PH", "Philippines", "63"),
    c("PL", "Poland", "48"), c("PT", "Portugal", "351"), c("PR", "Puerto Rico", "1787"),
    c("QA", "Qatar", "974"), c("RO", "Romania", "40"), c("RU", "Russia", "7"),
    c("RW", "Rwanda", "250"), c("WS", "Samoa", "685"), c("SM", "San Marino", "378"),
    c("SA", "Saudi Arabia", "966"), c("SN", "Senegal", "221"), c("RS", "Serbia", "381"),
    c("SC", "Seychelles", "248"), c("SL", "Sierra Leone", "232"), c("SG", "Singapore", "65"),
    c("SK", "Slovakia", "421"), c("SI", "Slovenia", "386"), c("SB", "Solomon Islands", "677"),
    c("SO", "Somalia", "252"), c("ZA", "South Africa", "27"), c("KR", "South Korea", "82"),
    c("SS", "South Sudan", "211"), c("ES", "Spain", "34"), c("LK", "Sri Lanka", "94"),
    c("SD", "Sudan", "249"), c("SR", "Suriname", "597"), c("SE", "Sweden", "46"),
    c("CH", "Switzerland", "41"), c("SY", "Syria", "963"), c("TW", "Taiwan", "886"),
    c("TJ", "Tajikistan", "992"), c("TZ", "Tanzania", "255"), c("TH", "Thailand", "66"),
    c("TL", "Timor-Leste", "670"), c("TG", "Togo", "228"), c("TO", "Tonga", "676"),
    c("TT", "Trinidad and Tobago", "1868"), c("TN", "Tunisia", "216"), c("TR", "Türkiye", "90"),
    c("TM", "Turkmenistan", "993"), c("UG", "Uganda", "256"), c("UA", "Ukraine", "380"),
    c("AE", "United Arab Emirates", "971"), c("GB", "United Kingdom", "44"), c("US", "United States", "1"),
    c("UY", "Uruguay", "598"), c("UZ", "Uzbekistan", "998"), c("VU", "Vanuatu", "678"),
    c("VE", "Venezuela", "58"), c("VN", "Vietnam", "84"), c("YE", "Yemen", "967"),
    c("ZM", "Zambia", "260"), c("ZW", "Zimbabwe", "263"),
).sortedBy { it.name }

/** Resolves a default country from a region code (e.g. "US"), falling back to the US. */
fun defaultCountryFor(region: String?): Country {
    val match = region?.uppercase()?.let { r -> countries.firstOrNull { it.iso == r } }
    return match ?: countries.first { it.iso == "US" }
}
