package com.showhow.ai

import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Canned answers, returned instantly. The app is fully demoable on these
 * tonight; tomorrow the real ones land behind the same four interfaces and
 * nothing above this package changes.
 */
class FakeAsr : Asr {
    override suspend fun transcribe(wav: File): List<Word> = listOf(
        Word("pehle", 0, 400),
        Word("dhakkan", 400, 900),
        Word("kholo", 900, 1400),
        Word("phir", 3000, 3400),
        Word("filter", 3400, 3900),
        Word("nikalo", 3900, 4400),
        Word("uske baad", 7000, 7700),
        Word("saaf", 7700, 8200),
        Word("karo", 8200, 8700),
    )
}

class FakeCaptioner : Captioner {
    private val canned = listOf(
        "Hand on the lid, turning it anticlockwise",
        "Filter basket lifted out of the housing",
        "Rinsing the basket under running water",
        "Basket back in place, lid closed",
    )
    private var n = 0
    override suspend fun caption(jpg: File): String = canned[n++ % canned.size]
}

class FakeGestureSource : GestureSource {
    override fun start(): Flow<Gesture> = flow {
        // One palm every couple of seconds: enough to walk a demo forward.
        while (true) {
            kotlinx.coroutines.delay(2000)
            emit(Gesture.OPEN_PALM)
        }
    }
}

class FakeSceneCheck : SceneCheck {
    override fun compare(live: Bitmap, saved: Bitmap): Float = 0.86f
}

fun fakeStack(): AiStack = AiStack(FakeAsr(), FakeCaptioner(), FakeGestureSource(), FakeSceneCheck())
