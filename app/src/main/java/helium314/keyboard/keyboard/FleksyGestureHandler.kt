// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.provider.UserDictionary
import android.widget.Toast
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.inputlogic.InputLogic

/**
 * Fleksy-style gestures on the letter keys:
 *  - swipe right: space (applies autocorrection); right after a word, a second swipe adds a full stop
 *  - swipe left:  delete the previous word
 *  - swipe down:  replace the last word with the next suggestion (or cycle punctuation after a full stop)
 *  - swipe up:    keep exactly what was typed and save it to the personal dictionary
 */
class FleksyGestureHandler(
    private val latinIME: LatinIME,
    private val inputLogic: InputLogic,
    private val listener: KeyboardActionListener,
    private val haptic: () -> Unit,
) {
    private val connection get() = inputLogic.connection

    // Candidates for the most recently committed word. Index 0 is always the literal typed word.
    private var candidates: List<String> = emptyList()
    private var shownIndex = -1

    fun onSwipe(direction: Int): Boolean {
        when (direction) {
            KeyboardActionListener.FLEKSY_RIGHT -> swipeRight()
            KeyboardActionListener.FLEKSY_LEFT -> {
                reset()
                listener.onCodeInput(KeyCode.DELETE_WORD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            KeyboardActionListener.FLEKSY_DOWN -> swipeDown()
            KeyboardActionListener.FLEKSY_UP -> swipeUp()
            else -> return false
        }
        haptic()
        return true
    }

    private fun swipeRight() {
        if (inputLogic.wordComposer.isComposingWord()) {
            commitWithSpace()
            return
        }
        reset()
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && Character.isLetterOrDigit(before[0])) {
            // "word |" -> "word. |"
            replaceBeforeCursor(1, ". ")
        } else {
            listener.onCodeInput(Constants.CODE_SPACE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    private fun swipeDown() {
        if (inputLogic.wordComposer.isComposingWord()) {
            commitWithSpace()
        }
        if (isStateValid() && candidates.size > 1) {
            showCandidate((shownIndex + 1) % candidates.size)
            return
        }
        reset()
        // cycle punctuation: "word. |" -> "word, |" -> ...
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ') {
            val idx = PUNCTUATION.indexOf(before[0])
            if (idx >= 0) replaceBeforeCursor(2, "${PUNCTUATION[(idx + 1) % PUNCTUATION.length]} ")
        }
    }

    private fun swipeUp() {
        if (inputLogic.wordComposer.isComposingWord()) {
            commitWithSpace()
        }
        val word = if (isStateValid()) {
            if (shownIndex != 0) showCandidate(0)
            candidates[0]
        } else {
            lastWordBeforeTrailingSpace()
        }
        reset()
        if (word != null && word.length > 1) saveWord(word)
    }

    /** Commit the composing word through the normal space path, remembering the alternatives. */
    private fun commitWithSpace() {
        val typed = inputLogic.wordComposer.getTypedWord()
        val suggested = inputLogic.suggestedWords
        val alternatives = (0 until suggested.size()).map { suggested.getWord(it) }
        listener.onCodeInput(Constants.CODE_SPACE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

        val committed = lastWordBeforeTrailingSpace()
        if (committed == null || typed.isEmpty()) {
            reset()
            return
        }
        val list = (listOf(typed, committed) + alternatives)
            .filter { it.isNotBlank() && !it.contains(' ') }
            .distinct()
            .take(MAX_CANDIDATES)
        candidates = list
        shownIndex = list.indexOf(committed)
    }

    private fun showCandidate(index: Int) {
        val current = candidates[shownIndex]
        replaceBeforeCursor(current.length + 1, "${candidates[index]} ")
        shownIndex = index
    }

    private fun isStateValid(): Boolean {
        if (shownIndex !in candidates.indices) return false
        val expected = candidates[shownIndex] + " "
        return connection.getTextBeforeCursor(expected.length, 0)?.toString() == expected
    }

    private fun replaceBeforeCursor(deleteCount: Int, text: String) {
        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(deleteCount)
        connection.commitText(text, 1)
        connection.endBatchEdit()
        KeyboardSwitcher.getInstance().requestUpdatingShiftState(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
    }

    private fun lastWordBeforeTrailingSpace(): String? {
        val text = connection.getTextBeforeCursor(64, 0)?.toString() ?: return null
        if (!text.endsWith(" ")) return null
        val body = text.dropLast(1)
        var start = body.length
        while (start > 0 && (Character.isLetterOrDigit(body[start - 1]) || body[start - 1] == '\'' || body[start - 1] == '-')) start--
        return body.substring(start).ifEmpty { null }
    }

    private fun saveWord(word: String) {
        runCatching {
            val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
            UserDictionary.Words.addWord(latinIME, word, 250, null, locale)
            Toast.makeText(latinIME, "\"$word\" ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        candidates = emptyList()
        shownIndex = -1
    }

    companion object {
        private const val PUNCTUATION = ".,?!:;"
        private const val MAX_CANDIDATES = 6
    }
}
