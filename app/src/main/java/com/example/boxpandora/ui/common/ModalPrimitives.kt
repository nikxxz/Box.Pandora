package com.example.boxpandora.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    showHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetBottomPadding = if (bottomInset > tokens.bottomPadding) bottomInset else tokens.bottomPadding
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val tentative = screenHeight - topInset - 8.dp
    val maxSheetHeight = if (tentative > 0.dp) tentative else screenHeight

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.background,
        scrimColor = tokens.scrim,
        tonalElevation = 0.dp,
        dragHandle = if (showHandle) ({ ModalHandle() }) else null,
        windowInsets = WindowInsets(0),
        shape = RoundedCornerShape(
            topStart = tokens.sheetTopRadius,
            topEnd = tokens.sheetTopRadius
        )
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.horizontalPadding)
                .padding(top = tokens.topPadding, bottom = sheetBottomPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
        ) {
            content()
        }
    }
}

@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    maxWidth: androidx.compose.ui.unit.Dp = 420.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val dialogBottomOffset = if (imeBottomInset > 0.dp) imeBottomInset / 3 else 0.dp
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f

    Dialog(
        onDismissRequest = {
            if (dismissOnBackPress || dismissOnClickOutside) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = 0.dp, bottom = dialogBottomOffset),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.scrim)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (dismissOnClickOutside) {
                            onDismiss()
                        }
                    }
            )
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .widthIn(max = maxWidth)
                    .padding(horizontal = tokens.horizontalPadding),
                shape = RoundedCornerShape(tokens.dialogRadius),
                color = tokens.background,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, tokens.border)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun ModalHandle() {
    val tokens = boxPandoraModalTokens()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tokens.handleTopMargin),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = tokens.handleWidth, height = tokens.handleHeight)
                .background(tokens.handleColor, RoundedCornerShape(999.dp))
        )
    }
}

@Composable
fun ModalHeader(
    title: String,
    subtitle: String? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val tokens = boxPandoraModalTokens()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = tokens.titleText
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.secondaryText.copy(alpha = 0.82f)
                )
            }
        }
        trailingContent?.let {
            Box(content = it)
        }
    }
}

@Composable
fun ModalSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        title?.let {
            Text(
                text = it.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.secondaryText.copy(alpha = 0.72f)
            )
        }
        content()
    }
}

@Composable
fun ModalActionRow(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    assetIcon: String? = null,
    iconTint: Color = Color.Unspecified,
    iconContainerColor: Color = Color.Unspecified,
    destructive: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val tokens = boxPandoraModalTokens()
    val contentColor = if (destructive) tokens.destructiveAccent else tokens.bodyText
    val resolvedIconTint = if (iconTint == Color.Unspecified) tokens.bodyText else iconTint
    val resolvedIconContainerColor = if (iconContainerColor == Color.Unspecified) {
        tokens.iconBackgroundNeutral
    } else {
        iconContainerColor
    }

    ModalRowSurface(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = tokens.rowMinHeight)
                .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (assetIcon != null || icon != null) {
                if (assetIcon != null) {
                    AppAssetIcon(
                        assetIcon = assetIcon,
                        tint = if (destructive) tokens.destructiveAccent else resolvedIconTint,
                        modifier = Modifier.size(18.dp)
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) tokens.destructiveAccent else resolvedIconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.secondaryText.copy(alpha = 0.84f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
fun ModalSelectableRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    assetIcon: String? = null,
    iconTint: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val tokens = boxPandoraModalTokens()

    ModalActionRow(
        label = label,
        modifier = modifier,
        supportingText = supportingText,
        icon = icon,
        assetIcon = assetIcon,
        iconTint = iconTint,
        trailingContent = if (selected) ({
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = tokens.selectedAccent,
                modifier = Modifier.size(18.dp)
            )
        }) else null,
        onClick = onClick
    )
}

@Composable
fun ModalRichRow(
    label: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    iconContainerColor: Color = Color.Unspecified,
    selected: Boolean = false,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null
) {
    val tokens = boxPandoraModalTokens()

    ModalActionRow(
        label = label,
        modifier = modifier,
        supportingText = supportingText,
        icon = icon,
        iconTint = iconTint,
        iconContainerColor = iconContainerColor,
        trailingContent = if (selected) ({
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = tokens.selectedAccent,
                modifier = Modifier.size(18.dp)
            )
        }) else if (trailingText != null) ({
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText
            )
        }) else null,
        onClick = onClick
    )
}

@Composable
fun ModalFooterAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    val tokens = boxPandoraModalTokens()

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) tokens.destructiveAccent.copy(alpha = 0.12f) else tokens.iconBackgroundNeutral,
            contentColor = if (destructive) tokens.destructiveAccent else tokens.bodyText
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (destructive) tokens.destructiveAccent else tokens.bodyText
        )
    }
}

@Composable
fun ModalDivider(modifier: Modifier = Modifier) {
    val tokens = boxPandoraModalTokens()
    HorizontalDivider(modifier = modifier, color = tokens.divider)
}

@Composable
fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val tokens = boxPandoraModalTokens()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                color = tokens.secondaryText,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tokens.iconBackgroundNeutral,
            unfocusedContainerColor = tokens.iconBackgroundNeutral,
            focusedBorderColor = tokens.border.copy(alpha = 0.9f),
            unfocusedBorderColor = tokens.border,
            focusedTextColor = tokens.bodyText,
            unfocusedTextColor = tokens.bodyText,
            cursorColor = tokens.selectedAccent
        )
    )
}

@Composable
fun ModalChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    trailingTint: Color = Color.Unspecified
) {
    val tokens = boxPandoraModalTokens()
    val resolvedTrailingTint = if (trailingTint == Color.Unspecified) tokens.secondaryText else trailingTint
    ModalRowSurface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        backgroundColor = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.bodyText
            )
            trailingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = resolvedTrailingTint,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun ModalRowSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    backgroundColor: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val resolvedBackground = if (backgroundColor == Color.Unspecified) {
        boxPandoraModalTokens().cardBackground
    } else {
        backgroundColor
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = resolvedBackground,
            tonalElevation = 0.dp
        ) {
            Box { content() }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = resolvedBackground,
            tonalElevation = 0.dp
        ) {
            Box { content() }
        }
    }
}

@Composable
private fun ModalIconChip(
    icon: ImageVector,
    tint: Color,
    containerColor: Color
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
fun AppContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val menuShape = RoundedCornerShape(18.dp)

    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = menuShape)) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .widthIn(min = 168.dp, max = 210.dp)
                .background(tokens.background, menuShape)
                .border(BorderStroke(1.dp, tokens.border), menuShape)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                content(this)
            }
        }
    }
}

@Composable
fun AppContextMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    assetIcon: String? = null,
    destructive: Boolean = false,
    selected: Boolean = false
) {
    val tokens = boxPandoraModalTokens()
    val contentColor = if (destructive) tokens.destructiveAccent else tokens.bodyText
    val itemShape = RoundedCornerShape(14.dp)
    val itemBackground = when {
        destructive -> Color.Transparent
        selected -> tokens.selectedAccent.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val iconTint = if (selected && !destructive) tokens.selectedAccent else contentColor

    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected && !destructive) tokens.selectedAccent else contentColor,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(itemBackground)
            .defaultMinSize(minHeight = 40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        leadingIcon = if (assetIcon != null) {
            {
                AppAssetIcon(
                    assetIcon = assetIcon,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            icon?.let {
                {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        trailingIcon = if (selected) ({
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(tokens.selectedAccent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = tokens.selectedAccent,
                    modifier = Modifier.size(10.dp)
                )
            }
        }) else null
    )
}

@Composable
fun AppContextMenuDivider() {
    val tokens = boxPandoraModalTokens()
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        color = tokens.divider
    )
}

@Composable
fun AppAssetIcon(
    assetIcon: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data("file:///android_asset/$assetIcon")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    )

    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}