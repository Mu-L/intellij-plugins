// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.lang.dart.ide.structure;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel;
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.analyzer.DartServerData;
import org.dartlang.analysis.server.protocol.Outline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

class DartStructureViewModel extends TextEditorBasedStructureViewModel implements StructureViewModel.ElementInfoProvider {

  private final @NotNull StructureViewTreeElement myRootElement;

  private final DartServerData.OutlineListener myListener = fileInfo -> {
    VirtualFile file = fileInfo.findFile();
    if (file != null && file.equals(getPsiFile().getVirtualFile())) {
      fireModelUpdate();
    }
  };


  DartStructureViewModel(final @Nullable Editor editor, final @NotNull PsiFile psiFile) {
    super(editor, psiFile);
    myRootElement = new DartStructureViewRootElement(psiFile);
    DartAnalysisServerService.getInstance(getPsiFile().getProject()).addOutlineListener(myListener);
  }

  @Override
  public void dispose() {
    DartAnalysisServerService.getInstance(getPsiFile().getProject()).removeOutlineListener(myListener);
    super.dispose();
  }

  @Override
  public @NotNull StructureViewTreeElement getRoot() {
    return myRootElement;
  }

  @Override
  public @Nullable PsiElement getCurrentEditorElement() {
    // Note: this should return an object of type PsiElement to be compatible with the Context Info (alt+q) action.
    if (getEditor() == null) return null;

    final DartAnalysisServerService service = DartAnalysisServerService.getInstance(getPsiFile().getProject());
    final Outline outline = service.getOutline(getPsiFile().getVirtualFile());
    if (outline == null) return null;

    final Outline result = findDeepestOutlineForOffset(getEditor().getCaretModel().getOffset(), outline);
    return DartStructureViewElement.findBestPsiElementForOutline(getPsiFile(), result);
  }

  private @NotNull Outline findDeepestOutlineForOffset(final int offset, final @NotNull Outline outline) {
    final DartAnalysisServerService service = DartAnalysisServerService.getInstance(getPsiFile().getProject());
    final VirtualFile file = getPsiFile().getVirtualFile();
    final List<Outline> children = outline.getChildren();
    if (children != null) {
      for (int i = 0; i < children.size(); i++) {
        Outline child = children.get(i);
        final int startOffset = service.getConvertedOffset(file, child.getOffset());
        final int endOffset = service.getConvertedOffset(file, child.getOffset() + child.getLength());
        if (offset >= startOffset && offset <= endOffset) {
          return findDeepestOutlineForOffset(offset, child);
        }

        // If we are between children, return the next.
        if (offset > endOffset && i != children.size() - 1) {
          final Outline next = children.get(i + 1);
          if (offset < service.getConvertedOffset(file, next.getOffset())) {
            return next;
          }
        }
      }
    }
    return outline;
  }

  @Override
  public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
    return false;
  }

  @Override
  public boolean isAlwaysLeaf(StructureViewTreeElement element) {
    return false;
  }

  @Override
  public Sorter @NotNull [] getSorters() {
    return new Sorter[]{Sorter.ALPHA_SORTER};
  }

  private static class DartStructureViewRootElement extends PsiTreeElementBase<PsiFile> {

    DartStructureViewRootElement(PsiFile file) {super(file);}

    @Override
    public @Nullable String getPresentableText() {
      return null;
    }

    @Override
    public @NotNull Collection<StructureViewTreeElement> getChildrenBase() {
      final DartAnalysisServerService service = DartAnalysisServerService.getInstance(getValue().getProject());
      final Outline outline = service.getOutline(getValue().getVirtualFile());
      return outline != null ? Arrays.asList(new DartStructureViewElement(getValue(), outline).getChildren())
                             : Collections.emptyList();
    }
  }
}
