// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.provider.UserDictionary
import android.widget.Toast
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.inputlogic.InputLogic

/**
 * Fleksy-style gestures on the letter keys:
 *  - swipe right: space (applies autocorrection) and shows the alternatives in the suggestion strip;
 *    a second swipe right after a word adds a full stop
 *  - swipe left:  delete the previous word
 *  - swipe down:  next alternative for the last word (or next punctuation mark after a full stop)
 *  - swipe up:    previous alternative; on the literal typed word, save it to the personal dictionary
 * The strip shows previous / current (highlighted, centre) / next while choosing.
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
    // What we last put in the suggestion strip; other suggestion updates are held back while it is valid.
    private var shownStrip: SuggestedWords? = null

    init {
        instance = this
    }

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
            // the autocorrected word is not the one wanted: commit and move straight to the next one
            commitWithSpace()
        }
        if (isStateValid()) {
            if (shownIndex < candidates.size - 1) showCandidate(shownIndex + 1) else showStrip()
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
        if (isStateValid()) {
            if (shownIndex > 0) showCandidate(shownIndex - 1) else saveWord(candidates[0])
            return
        }
        reset()
        lastWordBeforeTrailingSpace()?.let { saveWord(it) }
    }

    /** Called when a word in the suggestion strip is tapped. Returns true if it was one of our alternatives. */
    fun onSuggestionTapped(word: String): Boolean {
        if (shownStrip == null || !isStateValid()) return false
        if (word == SAVE_MARKER) {
            saveWord(candidates[0])
            return true
        }
        val idx = candidates.indexOf(word)
        if (idx < 0) return false
        showCandidate(idx)
        return true
    }

    /** True if [incoming] should not replace the alternatives currently shown in the strip. */
    fun blocksSuggestionUpdate(incoming: SuggestedWords): Boolean {
        val shown = shownStrip ?: return false
        if (incoming === shown) return false
        if (isStateValid()) return true
        reset()
        return false
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
        showStrip()
    }

    private fun showCandidate(index: Int) {
        val current = candidates[shownIndex]
        replaceBeforeCursor(current.length + 1, "${candidates[index]} ")
        shownIndex = index
        showStrip()
    }

    /** Strip layout with autocorrect on: index 1 is the highlighted centre, index 0 left, index 2+ right. */
    private fun showStrip() {
        val left = if (shownIndex == 0) SAVE_MARKER else candidates[shownIndex - 1]
        val words = listOf(left, candidates[shownIndex]) + candidates.drop(shownIndex + 1)
        val infos = ArrayList(words.mapIndexed { i, w ->
            SuggestedWordInfo(
                w, "", Int.MAX_VALUE - i,
                if (i == 0) SuggestedWordInfo.KIND_TYPED else SuggestedWordInfo.KIND_CORRECTION,
                Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
            )
        })
        val strip = SuggestedWords(
            infos, null, infos[0], false, true, false,
            SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER
        )
        shownStrip = strip
        latinIME.showFleksySuggestions(strip)
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
        if (word.length < 2) return
        runCatching {
            val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
            UserDictionary.Words.addWord(latinIME, word, 250, null, locale)
            Toast.makeText(latinIME, "\"$word\" ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        candidates = emptyList()
        shownIndex = -1
        shownStrip = null
    }

    companion object {
        private const val PUNCTUATION = ".,?!:;"
        private const val MAX_CANDIDATES = 8
        /** Shown left of the typed word: swipe up (or tap it) to save the word. */
        private const val SAVE_MARKER = "+"

        @Volatile
        var instance: FleksyGestureHandler? = null
            private set
    }
}
