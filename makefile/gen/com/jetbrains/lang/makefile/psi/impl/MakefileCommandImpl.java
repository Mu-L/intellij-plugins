// This is a generated file. Not intended for manual editing.
package com.jetbrains.lang.makefile.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.jetbrains.lang.makefile.psi.MakefileTypes.*;
import com.jetbrains.lang.makefile.psi.*;

public class MakefileCommandImpl extends MakefileCommandMixin implements MakefileCommand {

  public MakefileCommandImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MakefileVisitor visitor) {
    visitor.visitCommand(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MakefileVisitor) accept((MakefileVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<MakefileFunction> getFunctionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MakefileFunction.class);
  }

  @Override
  @NotNull
  public List<MakefileString> getStringList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MakefileString.class);
  }

  @Override
  @NotNull
  public List<MakefileSubstitution> getSubstitutionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MakefileSubstitution.class);
  }

  @Override
  @NotNull
  public List<MakefileVariableUsage> getVariableUsageList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MakefileVariableUsage.class);
  }

}
