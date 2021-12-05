/* Copyright (c) 2021 Seva Safris
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

package org.safris.intellij.eclipse_actions;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.swing.JComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.analysis.problemsView.toolWindow.ProblemsView;
import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorMouseHoverPopupManager;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LightweightHint;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.accessibility.AccessibleContextUtil;

public class SelectNextErrorHandler implements CodeInsightActionHandler {
  private static final Field navigationShiftField;
  private static final Method getSeveritiesCountMethod;

  static {
    try {
      navigationShiftField = HighlightInfo.class.getDeclaredField("navigationShift");
      navigationShiftField.setAccessible(true);

      getSeveritiesCountMethod = SeverityRegistrar.class.getDeclaredMethod("getSeveritiesCount");
      getSeveritiesCountMethod.setAccessible(true);
    }
    catch (final NoSuchFieldException | NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final boolean myGoForward;

  public SelectNextErrorHandler(final boolean goForward) {
    this.myGoForward = goForward;
  }

  @Override
  public void invoke(@NotNull final Project project, @NotNull final Editor editor, @NotNull final PsiFile file) {
    final SelectionModel selectionModel = editor.getSelectionModel();
    final int caretOffset = selectionModel.getSelectedText() != null ? selectionModel.getSelectionStart() + 1 : editor.getCaretModel().getOffset();
    this.gotoNextError(project, editor, file, caretOffset);
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  private void gotoNextError(final Project project, final Editor editor, final PsiFile file, final int caretOffset) {
    final SeverityRegistrar severityRegistrar = SeverityRegistrar.getSeverityRegistrar(project);
    final DaemonCodeAnalyzerSettings settings = DaemonCodeAnalyzerSettings.getInstance();
    int maxSeverity = 1;
    if (settings.isNextErrorActionGoesToErrorsFirst()) {
      try {
        maxSeverity = (Integer)getSeveritiesCountMethod.invoke(severityRegistrar) - 1;
      }
      catch (final IllegalAccessException | InvocationTargetException e) {
        e.printStackTrace();
      }
    }

    for(int idx = maxSeverity; idx >= 1; --idx) {
      final HighlightSeverity minSeverity = severityRegistrar.getSeverityByIndex(idx);
      if (minSeverity != null) {
        final HighlightInfo infoToGo = this.findInfo(project, editor, caretOffset, minSeverity);
        if (infoToGo != null) {
          navigateToError(project, editor, infoToGo, () -> {
            if (Registry.is("error.navigation.show.tooltip")) {
              final HighlightInfo fullInfo = ((DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(project)).findHighlightByOffset(editor.getDocument(), editor.getCaretModel().getOffset(), false);
              final HighlightInfo info = fullInfo != null ? fullInfo : infoToGo;
              EditorMouseHoverPopupManager.getInstance().showInfoTooltip(editor, info, editor.getCaretModel().getOffset(), false, true);
            }
          });
          return;
        }
      }
    }

    this.showMessageWhenNoHighlights(project, file, editor, caretOffset);
  }


  private HighlightInfo findInfo(@NotNull final Project project, @NotNull final Editor editor, final int caretOffset, @NotNull final HighlightSeverity minSeverity) {
    final Document document = editor.getDocument();
    final HighlightInfo[][] infoToGo = new HighlightInfo[2][2];
    final int caretOffsetIfNoLuck = this.myGoForward ? -1 : document.getTextLength();
    DaemonCodeAnalyzerEx.processHighlights(document, project, minSeverity, 0, document.getTextLength(), (info) -> {
      final int startOffset = getNavigationPositionFor(info, document);
      if (SeverityRegistrarHack.isGotoBySeverityEnabled(info.getSeverity())) {
        infoToGo[0][0] = this.getBetterInfoThan(infoToGo[0][0], caretOffset, startOffset, info);
        infoToGo[1][0] = this.getBetterInfoThan(infoToGo[1][0], caretOffsetIfNoLuck, startOffset, info);
      }

      infoToGo[0][1] = this.getBetterInfoThan(infoToGo[0][1], caretOffset, startOffset, info);
      infoToGo[1][1] = this.getBetterInfoThan(infoToGo[1][1], caretOffsetIfNoLuck, startOffset, info);
      return true;
    });

    if (infoToGo[0][0] == null) {
      infoToGo[0][0] = infoToGo[1][0];
    }

    if (infoToGo[0][1] == null) {
      infoToGo[0][1] = infoToGo[1][1];
    }

    if (infoToGo[0][0] == null) {
      infoToGo[0][0] = infoToGo[0][1];
    }

    return infoToGo[0][0];
  }

  private HighlightInfo getBetterInfoThan(final HighlightInfo infoToGo, final int caretOffset, final int startOffset, final HighlightInfo info) {
    return isBetterThan(infoToGo, caretOffset, startOffset) ? info : infoToGo;
  }

  private boolean isBetterThan(final HighlightInfo oldInfo, final int caretOffset, final int newOffset) {
    if (oldInfo == null)
      return true;

    final int oldOffset = getNavigationPositionFor(oldInfo, oldInfo.getHighlighter().getDocument());
    if (this.myGoForward)
      return caretOffset < oldOffset != caretOffset < newOffset ? caretOffset < newOffset : newOffset < oldOffset;

    return caretOffset <= oldOffset != caretOffset <= newOffset ? caretOffset > newOffset : newOffset > oldOffset;
  }

  private void showMessageWhenNoHighlights(final Project project, final PsiFile file, final Editor editor, final int caretOffset) {
    final DaemonCodeAnalyzerImpl codeHighlighter = (DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(project);
    final HintManagerImpl hintManager = HintManagerImpl.getInstanceImpl();
    if (codeHighlighter.isErrorAnalyzingFinished(file)) {
      hintManager.showInformationHint(editor, InspectionsBundle.message("no.errors.found.in.this.file"));
    }
    else {
      final JComponent component = HintUtil.createInformationLabel(InspectionsBundle.message("error.analysis.is.in.progress"), null, null, null);
      AccessibleContextUtil.setName(component, IdeBundle.message("information.hint.accessible.context.name"));
      final LightweightHint hint = new LightweightHint(component);
      final Point p = hintManager.getHintPosition(hint, editor, (short)1);
      final Disposable hintDisposable = Disposer.newDisposable("GotoNextErrorHandler.showMessageWhenNoHighlights");
      Disposer.register(project, hintDisposable);
      hint.addHintListener((eventObject) -> {
        Disposer.dispose(hintDisposable);
      });
      final MessageBusConnection busConnection = project.getMessageBus().connect(hintDisposable);
      busConnection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, new DaemonCodeAnalyzer.DaemonListener() {
        @Override
        public void daemonFinished() {
          hint.hide();
          SelectNextErrorHandler.this.gotoNextError(project, editor, file, caretOffset);
        }
      });
      hintManager.showEditorHint(hint, editor, p, 42, 0, false, (short)1);
    }
  }

  static void navigateToError(@NotNull final Project project, @NotNull final Editor editor, @NotNull final HighlightInfo info, @Nullable final Runnable postNavigateRunnable) {
    final int oldOffset = editor.getCaretModel().getOffset();
    final int offset = info.endOffset; //getNavigationPositionFor(info, editor.getDocument());
    final int endOffset = info.getActualEndOffset();
    final ScrollingModel scrollingModel = editor.getScrollingModel();
    if (offset != oldOffset) {
      final ScrollType scrollType = offset > oldOffset ? ScrollType.CENTER_DOWN : ScrollType.CENTER_UP;
      editor.getSelectionModel().removeSelection();
      editor.getCaretModel().removeSecondaryCarets();
      editor.getCaretModel().moveToOffset(offset);
      editor.getSelectionModel().setSelection(info.startOffset, info.endOffset);
      scrollingModel.scrollToCaret(scrollType);
      final FoldRegion regionAtOffset = editor.getFoldingModel().getCollapsedRegionAtOffset(offset);
      if (regionAtOffset != null) {
        editor.getFoldingModel().runBatchFoldingOperation(() -> {
          regionAtOffset.setExpanded(true);
        });
      }
    }

    scrollingModel.runActionOnScrollingFinished(() -> {
      final int maxOffset = editor.getDocument().getTextLength() - 1;
      if (maxOffset != -1) {
        scrollingModel.scrollTo(editor.offsetToLogicalPosition(Math.min(maxOffset, endOffset)), ScrollType.MAKE_VISIBLE);
        scrollingModel.scrollTo(editor.offsetToLogicalPosition(Math.min(maxOffset, offset)), ScrollType.MAKE_VISIBLE);
        if (postNavigateRunnable != null) {
          postNavigateRunnable.run();
        }
      }
    });
    IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation();
    final RangeHighlighterEx highlighter = info.getHighlighter();
    if (highlighter != null) {
      ProblemsView.selectHighlighterIfVisible(project, highlighter);
    }
  }

  private static int getNavigationPositionFor(final HighlightInfo info, final Document document) {
    final int start = info.getActualStartOffset();
    if (start >= document.getTextLength())
      return document.getTextLength();

    final char c = document.getCharsSequence().charAt(start);
    int shift = 1;
    if (info.isAfterEndOfLine() && c != '\n') {
      try {
        shift = navigationShiftField.getInt(info);
      }
      catch (final IllegalAccessException e) {
        e.printStackTrace();
      }
    }

    final int offset = info.getActualStartOffset() + shift;
    return Math.min(offset, document.getTextLength());
  }
}