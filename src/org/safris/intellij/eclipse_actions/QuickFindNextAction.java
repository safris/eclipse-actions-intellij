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

package org.safris.intellij.eclipse_actions;

import java.util.List;

import com.intellij.find.FindResult;

public class QuickFindNextAction extends QuickFindAction {
  private static int binaryClosestSearch(final List<FindResult> a, int from, int to, final int cursorOffset) {
    for (int mid; from < to;) {
      mid = (from + to) / 2;
      final int comparison = Integer.compare(cursorOffset, a.get(mid).getStartOffset());
      if (comparison < 0)
        to = mid;
      else if (comparison > 0)
        from = mid + 1;
      else
        return mid;
    }

    return (from + to) / 2;
  }

  @Override
  int getNextPrevious(final int cursorOffset) {
    final int size = findResults.size();
    final int index = binaryClosestSearch(findResults, 0, size, cursorOffset);
    return index >= size || index < 0 ? 0 : index;
  }

  @Override
  boolean isForward() {
    return true;
  }
}