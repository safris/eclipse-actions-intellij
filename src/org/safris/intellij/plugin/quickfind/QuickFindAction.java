/* Copyright (c) 2018 Seva Safris
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * You should have received a copy of The MIT License (MIT) along with this
 * program. If not, see <http://opensource.org/licenses/MIT/>.
 */

package org.safris.intellij.plugin.quickfind;

import java.awt.KeyboardFocusManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.intellij.find.EditorSearchSession;
import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.find.SearchReplaceComponent;
import com.intellij.find.impl.FindResultImpl;
import com.intellij.find.impl.livePreview.SearchResults;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

abstract class QuickFindAction extends AnAction {
  private static final FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();

  private static final DocumentListener documentListener = new DocumentListener() {
    @Override
    public void documentChanged(final DocumentEvent event) {
      // FIXME: Can be more specific here to have better performance.
      isDocumentChanged = true;
//      System.err.println("isDocumentChanged: true");
    }
  };

  private static final FindResult NULL_RESULT = new FindResultImpl(Integer.MAX_VALUE, Integer.MAX_VALUE);
  private static FindResult lastResult = NULL_RESULT;
  private static Document lastDocument;
  private static boolean isDocumentChanged = true;
  private static int findResultIndex;
  private static String lastSearchString;
  static final List<FindResult> findResults = new ArrayList<>();

  abstract boolean isForward();
  abstract int getNextPrevious(int cursorOffset);

  @Override
  public void actionPerformed(final AnActionEvent e) {
    final Editor editor = e.getData(PlatformDataKeys.EDITOR_EVEN_IF_INACTIVE);
    if (editor == null)
      return;

    if (lastDocument != editor.getDocument()) {
      if (lastDocument != null)
        lastDocument.removeDocumentListener(documentListener);

      lastDocument = editor.getDocument();
      lastDocument.addDocumentListener(documentListener);
      isDocumentChanged = true;
    }

    final Project project = e.getProject();
    if (project == null)
      return;

    FindManager findManager = null;
    EditorSearchSession session = EditorSearchSession.get(editor);
    if (session == null) {
      session = EditorSearchSession.start(editor, (findManager = FindManager.getInstance(project)).getFindInFileModel(), project);
//      System.err.println("new Session(\"" + session.getTextInField() + "\")");
    }

    boolean shouldSetTextInField = false;
    String textInField = null;
    if (session.getTextInField() != null && !session.getTextInField().isEmpty()) {
      textInField = session.getTextInField();
    }
    else if (lastSearchString != null) {
//      System.err.println("session.setTextInField(\"" + lastSearchString + "\")");
      shouldSetTextInField = true;
    }

    final SelectionModel selectionModel = editor.getSelectionModel();
    String nextSearchString = selectionModel.getSelectedText();
    final FindModel findModel = session.getFindModel();
    if (textInField != null) {
      if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner().getClass().getEnclosingClass() == SearchReplaceComponent.class) {
        nextSearchString = textInField;
      }
      else if (nextSearchString == null && areStringsDiff(findModel, textInField, lastSearchString)) {
        nextSearchString = textInField;
      }
      else {
        final String checkString = nextSearchString != null ? nextSearchString : lastSearchString;
        if (checkString != null && areStringsDiff(findModel, textInField, checkString))
          shouldSetTextInField = true;
      }
    }

    if (lastSearchString == null && nextSearchString == null) {
      return;
    }

    final boolean hasSearchString = nextSearchString != null;
    final boolean isFindStringChanged = hasSearchString && isDiffFromPriorSearch(findModel, nextSearchString);
//    System.err.println("actionPerformed(" + selectionModel.getSelectedText() + ", " + selectionModel.getSelectionEnd() + "): \"" + currentSelection + "\"");

    if (isDocumentChanged || isFindStringChanged) {
      isDocumentChanged = false;

//      System.err.println("isNewSearch \"" + lastSearchString + "\" -> \"" + nextSearchString + "\"");
      if (isFindStringChanged) {
        lastSearchString = nextSearchString;
        shouldSetTextInField = true;
      }

      if (findManager == null)
        findManager = FindManager.getInstance(project);

      refreshFindResults(editor, findManager, findModel);
    }

    final int caretOffset = editor.getCaretModel().getOffset();
    boolean isCaretAtEnd = caretOffset == selectionModel.getSelectionEnd();
    final boolean isForward = isForward();
    final int cursorOffset = !hasSearchString ? caretOffset : isForward ? selectionModel.getSelectionEnd() : selectionModel.getSelectionStart();

    final boolean hasBeenMoved = caretOffset < lastResult.getStartOffset() || lastResult.getEndOffset() < caretOffset;
//    System.err.println("hasBeenMoved: " + hasBeenMoved);

    if (hasBeenMoved) {
      findResultIndex = getNextPrevious(cursorOffset);
    }
    else if (isForward) {
      if (!hasSearchString && caretOffset == lastResult.getStartOffset())
        isCaretAtEnd = true;
      else if (++findResultIndex == findResults.size()) {
        findResultIndex = 0;
      }
    }
    else {
      if (!hasSearchString && caretOffset == lastResult.getEndOffset())
        isCaretAtEnd = false;
      else if (--findResultIndex == -1) {
        findResultIndex = findResults.size() - 1;
      }
    }

//    if (findResultIndex >= findResults.size())
//      return;

    final boolean isMoving = findResultIndex < findResults.size();
    if (isMoving) {
      lastResult = findResults.get(findResultIndex);

      // final boolean isLast = isForward ? caretOffset > lastResult.getStartOffset() : caretOffset < lastResult.getEndOffset();

      editor.getCaretModel().moveToOffset(isCaretAtEnd ? lastResult.getEndOffset() : lastResult.getStartOffset());
      selectionModel.setSelection(lastResult.getStartOffset(), lastResult.getEndOffset());
    }

    if (shouldSetTextInField)
      session.setTextInField(lastSearchString);

    if (isMoving)
      notifyCursorMoved(session, lastResult);

//    else {
//      go(session, isLast);
//    }

//    final boolean isLast = isForward ? cursorOffset > lastStartOffset : cursorOffset < firstEndOffset;
//    System.err.println("cursorOffset: " + cursorOffset + " [(" + firstStartOffset + "," + firstEndOffset + "),(" + lastStartOffset + "," + lastEndOffset + ")], isLast: " + isLast + ", stringToFind: \"" + findModel.getStringToFind() + "\" isCaseSensitive: " + findModel.isCaseSensitive());
//    invokeAction(e, actionId);
//    if (isLast)
//      invokeAction(e, actionId);
  }

  private static boolean isCaseSensitive;
  private static boolean isRegularExpression;
  private static boolean isWholeWordsOnly;

  private static boolean areStringsDiff(final FindModel findModel, final String a, final String b) {
    return findModel.isCaseSensitive() ? !a.equals(b) : !a.equalsIgnoreCase(b);
  }

  private static boolean isDiffFromPriorSearch(final FindModel findModel, final String currentSelection) {
    if (lastSearchString == null)
      return true;

    if (isRegularExpression != findModel.isRegularExpressions())
      return true;

    if (isCaseSensitive != findModel.isCaseSensitive())
      return true;

    if (isWholeWordsOnly != findModel.isWholeWordsOnly())
      return true;

    if (areStringsDiff(findModel, lastSearchString, currentSelection))
      return true;

    return false;
  }

  private static void refreshFindResults(final Editor editor, final FindManager findManager, final FindModel origFindModel) {
    final FindModel findModel = new FindModel();
    findModel.copyFrom(origFindModel);
    findModel.setStringToFind(lastSearchString);

    // This is a workaround for what seems to be a bug in IntelliJ's search mechanism.
    // When the search string is changed, not matter what, it will always be reported as `isLast` and `hasMatches() == false`,
    // regardless of whether it is last or if there are any matches. Therefore, when the search string changes, I do
    // a manual search myself to see if there is a match following the selection end. If there isn't, set `isChangedAndLast` to true,
    // so that the annoying "not found" tooltip is skipped.
    final Document document = editor.getDocument();
    final VirtualFile virtualFile = fileDocumentManager.getFile(document);
    final CharSequence charSequence = document.getImmutableCharSequence();
    int lastEndOffset = 0;
    findResults.clear();
    do {
      final FindResult findResult = findManager.findString(charSequence, lastEndOffset, findModel, virtualFile);
//      System.err.println("findResult \"" + findModel.getStringToFind() + "\": " + findResult);
      if (findResult.getEndOffset() == 0)
        break;

      findResults.add(findResult);
      lastEndOffset = findResult.getEndOffset();
    }
    while (true);

    lastResult = NULL_RESULT;
    isRegularExpression = findModel.isRegularExpressions();
    isCaseSensitive = findModel.isCaseSensitive();
    isWholeWordsOnly = findModel.isWholeWordsOnly();
  }

  private static final Field mySearchResultsField;
  private static final Field myCursorField;
  private static final Method updateSelectionMethod;
  private static final Method notifyCursorMovedMethod;

  static {
    try {
      mySearchResultsField = EditorSearchSession.class.getDeclaredField("mySearchResults");
      mySearchResultsField.setAccessible(true);
      myCursorField = SearchResults.class.getDeclaredField("myCursor");
      myCursorField.setAccessible(true);
      updateSelectionMethod = SearchResults.class.getDeclaredMethod("updateSelection", boolean.class, boolean.class, boolean.class);
      updateSelectionMethod.setAccessible(true);
      notifyCursorMovedMethod = SearchResults.class.getDeclaredMethod("notifyCursorMoved");
      notifyCursorMovedMethod.setAccessible(true);
    }
    catch (final NoSuchFieldException | NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private void notifyCursorMoved(final EditorSearchSession session, final FindResult cursor) {
    try {
      final SearchResults searchResults = (SearchResults)mySearchResultsField.get(session);
      myCursorField.set(searchResults, cursor);
      updateSelectionMethod.invoke(searchResults, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
      notifyCursorMovedMethod.invoke(searchResults);
    }
    catch (final Exception e) {
      e.printStackTrace();
    }
  }

  private void invokeActionViaSession(final EditorSearchSession session, final boolean isLast) {
//    System.err.println("XXX: " + session.isSearchInProgress());
    invokeActionViaSession(session);
    if (isLast)
      invokeActionViaSession(session);
  }

  private void invokeActionViaSession(final EditorSearchSession session) {
    if (getClass() == QuickFindNextAction.class)
      session.searchForward();
    else
      session.searchBackward();
  }

  private static void invokeAction(final AnActionEvent e, final String navigationActionId) {
    e.getActionManager().getAction(navigationActionId).actionPerformed(e);
  }
}