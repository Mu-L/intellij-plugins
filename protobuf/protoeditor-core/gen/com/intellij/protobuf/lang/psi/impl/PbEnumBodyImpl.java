// This is a generated file. Not intended for manual editing.
package com.intellij.protobuf.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.intellij.protobuf.lang.psi.PbTypes.*;
import com.intellij.protobuf.lang.psi.*;
import com.intellij.protobuf.lang.psi.util.PbPsiImplUtil;
import static com.intellij.protobuf.lang.psi.ProtoTokenTypes.*;

public class PbEnumBodyImpl extends PbEnumBodyMixin implements PbEnumBody {

  public PbEnumBodyImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull PbVisitor visitor) {
    visitor.visitEnumBody(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof PbVisitor) accept((PbVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  @Unmodifiable
  public List<PbEnumReservedStatement> getEnumReservedStatementList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, PbEnumReservedStatement.class);
  }

  @Override
  @NotNull
  public List<PbEnumValue> getEnumValueList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, PbEnumValue.class);
  }

  @Override
  @NotNull
  public List<PbOptionStatement> getOptionStatements() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, PbOptionStatement.class);
  }

  @Override
  @NotNull
  public PsiElement getStart() {
    return notNullChild(findChildByType(LBRACE));
  }

  @Override
  @Nullable
  public PsiElement getEnd() {
    return findChildByType(RBRACE);
  }

}
