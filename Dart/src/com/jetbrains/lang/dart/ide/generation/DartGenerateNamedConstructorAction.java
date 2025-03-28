// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.lang.dart.ide.generation;

import org.jetbrains.annotations.NotNull;

public class DartGenerateNamedConstructorAction extends BaseDartGenerateAction {
  @Override
  protected @NotNull BaseDartGenerateHandler getGenerateHandler() {
    return new DartGenerateNamedConstructorHandler();
  }
}
