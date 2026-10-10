package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** Typed credentials belong to composition memory, never Bundle or saved instance state. */
@Composable
fun rememberSensitiveInput(): MutableState<String> = remember { mutableStateOf("") }
