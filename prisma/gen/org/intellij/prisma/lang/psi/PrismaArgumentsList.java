// This is a generated file. Not intended for manual editing.
package org.intellij.prisma.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface PrismaArgumentsList extends PrismaElement {

  @NotNull
  List<PrismaArgument> getArguments();

  @Nullable PrismaNamedArgument findArgumentByName(@NotNull String name);

}
