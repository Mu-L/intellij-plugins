// This is a generated file. Not intended for manual editing.
package com.jetbrains.lang.makefile.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface MakefileRule extends PsiElement {

  @Nullable
  MakefileRecipe getRecipe();

  @NotNull
  MakefileTargetLine getTargetLine();

  @NotNull List<@NotNull MakefileTarget> getTargets();

}
