package com.cookiegames.smartcookie.js

import com.anthonycr.mezzanine.FileStream


@FileStream("app/src/main/js/TranslateModifier.js")
interface Translate {

    fun provideJs(): String

}