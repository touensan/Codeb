package com.codex.remote.ui

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

object UiText { var context: Context? = null }

fun uiText(@StringRes id: Int, fallback: String, vararg args: Any): String =
    UiText.context?.getString(id, *args) ?: if (args.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *args)
