package id.homebase.core.widget

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.clear_input
import id.homebase.resources.menu_back
import id.homebase.resources.search
import org.jetbrains.compose.resources.stringResource

@Composable
fun StyledSearchTextField(
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState,
    showSearchIcon: Boolean = true,
    showBackButton: Boolean = false,
    placeHolderText: String,
    onBackButtonClick: () -> Unit = {}
) {
    TextField(
        state = textFieldState,
        modifier = modifier,
        // Not LocalTextStyle: both search bars sit in a TopAppBar title slot, which provides titleLarge (22sp).
        textStyle = MaterialTheme.typography.bodyLarge,
        lineLimits = TextFieldLineLimits.SingleLine,
        placeholder = {
            Text(placeHolderText)
        },
        leadingIcon = if (!showBackButton && !showSearchIcon) null else {
            {
                if (showBackButton) {
                    IconButton(onClick = onBackButtonClick, modifier = Modifier.testTag("back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                } else if (showSearchIcon) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(MR.string.search),
                        modifier = Modifier.testTag("search_icon")
                    )
                }
            }
        },
        trailingIcon = {
            if (textFieldState.text.isNotEmpty()) {
                IconButton(onClick = { textFieldState.clearText() }, modifier = Modifier.testTag("clear_input_button")) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.clear_input)
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default
        )
    )
}

@Composable
fun MinimalSearchTextField(
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState,
    showBackButton: Boolean = false,
    placeHolderText: String,
    onBackButtonClick: () -> Unit = {}
) {
    StyledSearchTextField(
        modifier = modifier.heightIn(max = 40.dp),
        textFieldState = textFieldState,
        showBackButton = showBackButton,
        placeHolderText = placeHolderText,
        onBackButtonClick = onBackButtonClick
    )
}