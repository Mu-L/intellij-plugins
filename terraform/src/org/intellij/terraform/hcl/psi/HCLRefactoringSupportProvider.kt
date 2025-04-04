// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.hcl.psi

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement

class HCLRefactoringSupportProvider : RefactoringSupportProvider() {
  override fun isAvailable(context: PsiElement): Boolean {
    return context is HCLElement && context is PsiNamedElement
  }
}