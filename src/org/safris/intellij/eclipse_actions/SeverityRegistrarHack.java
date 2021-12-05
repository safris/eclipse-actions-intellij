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

import java.util.Iterator;

import org.jetbrains.annotations.NotNull;

import com.intellij.codeInsight.daemon.impl.SeveritiesProvider;
import com.intellij.lang.annotation.HighlightSeverity;

public class SeverityRegistrarHack {
  static boolean isGotoBySeverityEnabled(@NotNull final HighlightSeverity minSeverity) {
    final Iterator<SeveritiesProvider> var1 = SeveritiesProvider.EP_NAME.getIterable().iterator();

    SeveritiesProvider provider;
    do {
      if (!var1.hasNext())
        return minSeverity != HighlightSeverity.INFORMATION;

      provider = var1.next();
    }
    while (!provider.isGotoBySeverityEnabled(minSeverity));

    return true;
  }
}