package com.maestro.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.maestro.app.R

private val Garamond = FontFamily(
    Font(R.font.ebgaramond12_regular, FontWeight.Normal),
    Font(R.font.ebgaramond12_bold, FontWeight.Bold)
)

private val base = Typography()

private fun TextStyle.heading(size: Int, line: Int, weight: FontWeight) = copy(
    fontFamily = Garamond,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp
)

// The serif is for headings only; body, labels and controls stay in the system sans.
val AmadeusTypography = Typography(
    headlineLarge = base.headlineLarge.heading(38, 44, FontWeight.Normal),
    headlineMedium = base.headlineMedium.heading(34, 40, FontWeight.Normal),
    headlineSmall = base.headlineSmall.heading(28, 34, FontWeight.Normal),
    titleLarge = base.titleLarge.heading(24, 30, FontWeight.Bold),
    titleMedium = base.titleMedium.heading(20, 26, FontWeight.Bold)
)
