// This is a generated file. Not intended for manual editing.
package org.intellij.erlang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ErlangFunClause extends ErlangCompositeElement {

  @NotNull
  ErlangArgumentList getArgumentList();

  @NotNull
  ErlangClauseBody getClauseBody();

  @Nullable
  ErlangGuard getGuard();

  @Nullable
  PsiElement getWhen();

}