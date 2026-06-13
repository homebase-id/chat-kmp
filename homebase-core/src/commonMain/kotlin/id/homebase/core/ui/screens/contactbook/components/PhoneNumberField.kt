package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.contactbook_country_search
import org.jetbrains.compose.resources.stringResource

/**
 * Friendly phone entry: a country-code selector (flag + dial code) plus a national
 * number field. Emits a normalized **E.164** string (e.g. `+14155550123`) via
 * [onValueChange], or `""` when the number is blank — so the user never types `+` or
 * the country code. Defaults the country from the device region.
 */
@Composable
fun PhoneNumberField(
    e164Value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val region = Locale.current.region
    // Seed country/national from the incoming value once; the field owns them after.
    val seeded = remember { splitE164(e164Value) }
    var country by remember { mutableStateOf(seeded.first ?: defaultCountryFor(region)) }
    var national by remember { mutableStateOf(seeded.second) }
    var pickerOpen by remember { mutableStateOf(false) }

    fun emit(c: Country, n: String) {
        val digits = n.filter { it.isDigit() }
        onValueChange(if (digits.isBlank()) "" else "+${c.dialCode}$digits")
    }

    OutlinedTextField(
        value = national,
        onValueChange = {
            national = it.filter { ch -> ch.isDigit() || ch == ' ' }
            emit(country, national)
        },
        label = { Text(label) },
        singleLine = true,
        leadingIcon = {
            Row(
                modifier = Modifier
                    .clickable { pickerOpen = true }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val codeLabel = "${country.flag} +${country.dialCode}"
                Text(codeLabel)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = modifier,
    )

    if (pickerOpen) {
        CountryPickerSheet(
            onSelect = { c ->
                country = c
                pickerOpen = false
                emit(c, national)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun CountryPickerSheet(
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(MR.string.contactbook_country_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            val q = query.trim()
            val filtered = remember(q) {
                if (q.isBlank()) countries
                else countries.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.dialCode.startsWith(q.removePrefix("+"))
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                items(filtered, key = { it.iso }) { c ->
                    val dial = "+${c.dialCode}"
                    ListItem(
                        modifier = Modifier.clickable { onSelect(c) },
                        leadingContent = { Text(c.flag) },
                        headlineContent = { Text(c.name) },
                        trailingContent = { Text(dial) },
                    )
                }
            }
        }
    }
}
