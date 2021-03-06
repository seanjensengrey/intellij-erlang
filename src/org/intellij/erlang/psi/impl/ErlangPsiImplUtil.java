/*
 * Copyright 2012 Sergey Ignatov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.erlang.psi.impl;

import com.intellij.codeInsight.completion.BasicInsertHandler;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.erlang.ErlangFileType;
import org.intellij.erlang.ErlangIcons;
import org.intellij.erlang.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErlangPsiImplUtil {
  private ErlangPsiImplUtil() {
  }

  @SuppressWarnings("UnusedParameters")
  public static boolean processDeclarations(@NotNull ErlangQVar o, @NotNull PsiScopeProcessor processor, @NotNull ResolveState state, PsiElement lastParent, @NotNull PsiElement place) {
    return processor.execute(o, state);
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangQVar o) {
    return new ErlangVariableReferenceImpl(o, TextRange.from(0, o.getTextLength()));
  }

  public static List<ErlangTypedExpr> getRecordFields(PsiElement element) {
    List<ErlangTypedExpr> result = new ArrayList<ErlangTypedExpr>(0);
    ErlangRecordExpression recordExpression = PsiTreeUtil.getParentOfType(element, ErlangRecordExpression.class);
    ErlangQAtom atomName = recordExpression != null ? recordExpression.getAtomName() : null;
    PsiReference reference = atomName != null ? atomName.getReference() : null;
    PsiElement resolve = reference != null ? reference.resolve() : null;

    if (resolve instanceof ErlangRecordDefinition) {
      ErlangTypedRecordFields typedRecordFields = ((ErlangRecordDefinition) resolve).getTypedRecordFields();
      if (typedRecordFields != null) {
        for (ErlangTypedExpr e : typedRecordFields.getTypedExprList()) {
          result.add(e);
        }
      }
    }

    return result;
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangRecordField o) {
    final ErlangQAtom atom = o.getFieldNameAtom();
    return new ErlangAtomBasedReferenceImpl<ErlangQAtom>(atom, TextRange.from(0, atom.getTextLength()), atom.getText()) {
      @Override
      public PsiElement resolve() {
        List<ErlangTypedExpr> fields = getRecordFields(myElement);
        for (ErlangTypedExpr field : fields) {
          if (field.getName().equals(myReferenceName)) return field;
        }
        return null;
      }

      @NotNull
      @Override
      public Object[] getVariants() {
        return new Object[0];
      }
    };
  }

  @Nullable
  public static PsiReference getReference(@NotNull final ErlangIncludeString o) {
    final PsiElement parent = o.getParent();
    if (o.getTextLength() >= 2 && parent instanceof ErlangInclude) {
      return new PsiReferenceBase<PsiElement>(o, TextRange.from(1, o.getTextLength() - 2)) {
        @Override
        public PsiElement resolve() {
          return ContainerUtil.getFirstItem(filesFromInclude((ErlangInclude) parent));
        }

        @NotNull
        @Override
        public Object[] getVariants() {
          return new Object[0];
        }
      };
    }
    return null;
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangFunctionCallExpression o) {
    PsiElement parent = o.getParent();
    ErlangModuleRef moduleReference = null;
    if (parent instanceof ErlangGlobalFunctionCallExpression) {
      moduleReference = ((ErlangGlobalFunctionCallExpression) parent).getModuleRef();
    }
    ErlangQAtom moduleAtom = moduleReference == null ? null : moduleReference.getQAtom();
    ErlangQAtom nameAtom = o.getQAtom();

    return new ErlangFunctionReferenceImpl<ErlangQAtom>(
      nameAtom, moduleAtom, TextRange.from(0, nameAtom.getTextLength()),
      nameAtom.getText(), o.getArgumentList().getExpressionList().size());
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangFunctionWithArity o) {
    ErlangModuleRef moduleReference = PsiTreeUtil.getPrevSiblingOfType(o, ErlangModuleRef.class);
    ErlangQAtom moduleAtom = moduleReference == null ? null : moduleReference.getQAtom();
    ErlangQAtom nameAtom = o.getQAtom();

    PsiElement arity = o.getInteger();
    return new ErlangFunctionReferenceImpl<ErlangQAtom>(nameAtom, moduleAtom, TextRange.from(0, nameAtom.getTextLength()),
      nameAtom.getText(), StringUtil.parseInt(arity == null ? "" : arity.getText(), -1));
  }

  @NotNull
  public static PsiReference getReference(@NotNull ErlangExportFunction o) {
    PsiElement arity = o.getInteger();
    return new ErlangFunctionReferenceImpl<ErlangQAtom>(o.getQAtom(), null, TextRange.from(0, o.getQAtom().getTextLength()),
      o.getQAtom().getText(), StringUtil.parseInt(arity == null ? "" : arity.getText(), -1));
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangMacros o) {
    ErlangMacrosName macrosName = o.getMacrosName();
    if (macrosName == null) return null;
    return new ErlangMacrosReferenceImpl<ErlangMacrosName>(macrosName, TextRange.from(0, macrosName.getTextLength()), macrosName.getText());
  }

  public static boolean inDefinition(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangArgumentDefinition.class) != null;
  }

  @SuppressWarnings("unchecked")
  public static boolean inArgumentList(PsiElement psiElement) {
    ErlangArgumentList argList = PsiTreeUtil.getParentOfType(psiElement, ErlangArgumentList.class, true,
      ErlangFunctionCallExpression.class, ErlangFunClause.class, ErlangListComprehension.class);
    return (argList != null ? argList.getParent() : null) instanceof ErlangFunctionCallExpression;
  }

  public static boolean inDefine(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangMacrosDefinition.class) != null;
  }

  public static boolean inCallback(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangCallbackSpec.class) != null;
  }

  public static boolean inAtomAttribute(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangAtomAttribute.class) != null;
  }

  public static boolean inSpecification(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangSpecification.class) != null;
  }

  public static boolean inColonQualified(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangColonQualifiedExpression.class) != null;
  }

  public static boolean isLeftPartOfAssignment(@NotNull PsiElement psiElement) {
    ErlangAssignmentExpression assignmentExpression = PsiTreeUtil.getParentOfType(psiElement, ErlangAssignmentExpression.class);
    if (assignmentExpression == null) return false;
    return PsiTreeUtil.isAncestor(assignmentExpression.getLeft(), psiElement, false);
  }

  public static boolean isMacros(ErlangQVar o) {
    return o.getName().startsWith("?");
  }

  public static boolean isForceSkipped(ErlangQVar o) {
    return o.getName().startsWith("_");
  }

  @NotNull
  public static List<LookupElement> getFunctionLookupElements(@NotNull PsiFile containingFile, final boolean withArity, @Nullable ErlangColonQualifiedExpression colonQualifier) {
    if (containingFile instanceof ErlangFile) {
      List<ErlangFunction> functions = new ArrayList<ErlangFunction>();

      if (colonQualifier != null) {
        ErlangExpression qAtom = ContainerUtil.getFirstItem(colonQualifier.getExpressionList());
        if (qAtom != null) {
          functions.addAll(getExternalFunctionForCompletion(containingFile.getProject(), qAtom.getText() + ".erl"));
        }
      } else {
        functions.addAll(((ErlangFile) containingFile).getFunctions());
      }

      List<LookupElement> lookupElements = ContainerUtil.map(functions, new Function<ErlangFunction, LookupElement>() {
        @Override
        public LookupElement fun(@NotNull final ErlangFunction function) {
          return createFunctionLookupElement(function.getName(), function.getArity(), withArity);
        }
      });

      if (!withArity && colonQualifier == null) {
        // todo: move to more appropriate place
        PsiFile[] erlInternals = FilenameIndex.getFilesByName(containingFile.getProject(), "erl_internal.erl",
          GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(containingFile.getProject()), ErlangFileType.INSTANCE));

        if (erlInternals.length == 1) {
          for (String line : StringUtil.splitByLines(erlInternals[0].getText())) {
            Pattern bifPattern = Pattern.compile("bif\\((\\w+), (\\d+)\\) -> true;");
            Matcher m = bifPattern.matcher(line);
            if (m.matches()) {
              String name = m.group(1);
              int arity = Integer.parseInt(m.group(2));
              lookupElements.add(createFunctionLookupElement(name, arity, withArity));
            }
          }
        }
      }

      return lookupElements;
    }
    return Collections.emptyList();
  }

  private static LookupElement createFunctionLookupElement(String name, int arity, boolean withArity) {
    return LookupElementBuilder.create(name)
      .setIcon(ErlangIcons.FUNCTION).setTailText("/" + arity).
        setInsertHandler(
          getInsertHandler(arity, withArity)
        );
  }

  private static InsertHandler<LookupElement> getInsertHandler(final int arity, boolean withArity) {
    return withArity ?
      new BasicInsertHandler<LookupElement>() {
        @Override
        public void handleInsert(InsertionContext context, LookupElement item) {
          final Editor editor = context.getEditor();
          final Document document = editor.getDocument();
          context.commitDocument();
          document.insertString(context.getTailOffset(), "/" + arity);
          editor.getCaretModel().moveToOffset(context.getTailOffset());
        }
      } :
      new ParenthesesInsertHandler<LookupElement>() {
        @Override
        protected boolean placeCaretInsideParentheses(InsertionContext context, LookupElement item) {
          return arity > 0;
        }
      };
  }

  @NotNull
  public static List<LookupElement> getMacrosLookupElements(@NotNull PsiFile containingFile) {
    if (containingFile instanceof ErlangFile) {
      List<ErlangMacrosDefinition> concat = ContainerUtil.concat(((ErlangFile) containingFile).getMacroses(), getErlangMacrosesFromIncludes((ErlangFile) containingFile, true, ""));
      return ContainerUtil.map(
        concat,
        new Function<ErlangMacrosDefinition, LookupElement>() {
          @Override
          public LookupElement fun(@NotNull ErlangMacrosDefinition md) {
            return LookupElementBuilder.create(md).setIcon(ErlangIcons.MACROS);
          }
        });
    }
    return Collections.emptyList();
  }

  @NotNull
  public static List<LookupElement> getRecordLookupElements(@NotNull PsiFile containingFile) {
    if (containingFile instanceof ErlangFile) {
      List<ErlangRecordDefinition> concat = ContainerUtil.concat(((ErlangFile) containingFile).getRecords(), getErlangRecordFromIncludes((ErlangFile) containingFile, true, ""));
      return ContainerUtil.map(
        concat,
        new Function<ErlangRecordDefinition, LookupElement>() {
          @Override
          public LookupElement fun(@NotNull ErlangRecordDefinition rd) {
            return LookupElementBuilder.create(rd).setIcon(ErlangIcons.RECORD);
          }
        });
    }
    return Collections.emptyList();
  }

  @NotNull
  public static String getName(@NotNull ErlangFunction o) {
    PsiElement atom = o.getAtomName().getAtom();
    if (atom != null) {
      return atom.getText();
    }
    //noinspection ConstantConditions
    return o.getAtomName().getMacros().getText();
  }

  @NotNull
  public static String getName(@NotNull ErlangQVar o) {
    return o.getText();
  }

  public static int getArity(@NotNull ErlangFunction o) {
    return o.getFunctionClauseList().get(0).getArgumentDefinitionList().getArgumentDefinitionList().size();
  }

  @NotNull
  public static String getName(@NotNull ErlangRecordDefinition o) {
    ErlangQAtom atom = o.getQAtom();
    if (atom == null) return "";
    return atom.getText();
  }

  @NotNull
  public static PsiElement getNameIdentifier(@NotNull ErlangRecordDefinition o) {
    ErlangQAtom atom = o.getQAtom();
    return atom != null ? atom : o;
  }

  public static int getTextOffset(@NotNull ErlangRecordDefinition o) {
    if (o.getNameIdentifier() == o) return 0;//o.getNode().getTextOffset();
    return o.getNameIdentifier().getTextOffset();
  }

  @NotNull
  public static PsiElement getNameIdentifier(@NotNull ErlangQVar o) {
    return o;
  }

  @NotNull
  public static PsiElement getNameIdentifier(@NotNull ErlangFunction o) {
    return o.getAtomName();
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangRecordExpression o) { // todo: hack?
    ErlangQAtom atom = o.getAtomName();
    if (atom == null) return null;
    // case for #?macro_record_name{id=10}
    atom.setReference(new ErlangRecordReferenceImpl<ErlangQAtom>(atom, atom.getMacros() == null ? TextRange.from(0, atom.getTextLength()) : TextRange.from(0, 1) , atom.getText()));
    return null;
  }

  @Nullable
  public static PsiReference getReference(@NotNull ErlangModuleRef o) {
    ErlangQAtom atom = o.getQAtom();
    return new ErlangModuleReferenceImpl<ErlangQAtom>(atom,
      TextRange.from(0, atom.getTextLength()), atom.getText() + ".erl");
  }

  @NotNull
  public static PsiElement setName(@NotNull ErlangFunction o, @NotNull String newName) {
    for (ErlangFunctionClause clause : o.getFunctionClauseList()) {
      PsiElement atom = clause.getQAtom().getAtom();
      if (atom != null) {
        atom.replace(ErlangElementFactory.createQAtomFromText(o.getProject(), newName));
      }
    }
    return o;
  }

  @NotNull
  public static PsiElement setName(@NotNull ErlangQVar o, @NotNull String newName) {
    o.replace(ErlangElementFactory.createQVarFromText(o.getProject(), newName));
    return o;
  }

  @NotNull
  public static PsiElement setName(@NotNull ErlangRecordDefinition o, @NotNull String newName) {
    ErlangQAtom qAtom = o.getQAtom();
    if (qAtom != null) {
      PsiElement atom = qAtom.getAtom();
      if (atom != null) {
        atom.replace(ErlangElementFactory.createQAtomFromText(o.getProject(), newName));
      }
    }
    return o;
  }

  @NotNull
  public static String getName(@NotNull ErlangModule o) {
    ErlangQAtom atom = o.getQAtom();
    return atom == null ? "" : atom.getText();
  }

  @NotNull
  public static PsiElement setName(@NotNull ErlangModule o, String newName) {
    VirtualFile virtualFile = o.getContainingFile().getVirtualFile();
    if (virtualFile != null) {
      try {
        String ext = FileUtil.getExtension(virtualFile.getName());
        virtualFile.rename(o, newName + "." + ext);

        ErlangQAtom qAtom = o.getQAtom();
        if (qAtom != null) {
          PsiElement atom = qAtom.getAtom();
          if (atom != null) {
            atom.replace(ErlangElementFactory.createQAtomFromText(o.getProject(), newName));
          }
        }
      } catch (IOException ignored) {
      }
    }
    return o;
  }

  @NotNull
  public static PsiElement getNameIdentifier(@NotNull ErlangModule o) {
    ErlangQAtom atom = o.getQAtom();
    return atom == null ? o : atom;
  }

  public static int getTextOffset(@NotNull ErlangModule o) {
    if (o.getNameIdentifier() == o) return 0; //o.getNode().getTextOffset();
    return o.getNameIdentifier().getTextOffset();
  }

  @NotNull
  public static PsiElement getNameIdentifier(@NotNull ErlangFunctionCallExpression o) {
    return o.getQAtom();
  }

  public static int getTextOffset(@NotNull ErlangFunctionCallExpression o) {
    return o.getQAtom().getTextOffset();
  }

  @SuppressWarnings("UnusedParameters")
  public static boolean processDeclarations(@NotNull ErlangListComprehension o, @NotNull PsiScopeProcessor processor, @NotNull ResolveState state, PsiElement lastParent, @NotNull PsiElement place) {
    return processDeclarationRecursive(o, processor, state);
  }

  @SuppressWarnings("UnusedParameters")
  public static boolean processDeclarations(@NotNull ErlangModule o, @NotNull PsiScopeProcessor processor, @NotNull ResolveState state, PsiElement lastParent, @NotNull PsiElement place) {
    return processDeclarationRecursive(o, processor, state);
  }

  private static boolean processDeclarationRecursive(ErlangCompositeElement o, PsiScopeProcessor processor, ResolveState state) {
    Queue<ErlangCompositeElement> queue = new LinkedList<ErlangCompositeElement>();
    queue.add(o);
    while (!queue.isEmpty()) {
      ErlangCompositeElement top = queue.remove();
      if (!processor.execute(top, state)) return false;
      queue.addAll(PsiTreeUtil.getChildrenOfTypeAsList(top, ErlangCompositeElement.class));
    }
    return true;
  }

  @Nullable
  public static ErlangModule getModule(PsiFile file) {
    if (file instanceof ErlangFile) {
      List<ErlangAttribute> attributes = PsiTreeUtil.getChildrenOfTypeAsList(file, ErlangAttribute.class);
      for (ErlangAttribute attribute : attributes) {
        ErlangModule module = attribute.getModule();
        if (module != null) {
          return module;
        }
      }
    }
    return null;
  }

  static boolean isInModule(PsiElement psiElement) {
    return PsiTreeUtil.getParentOfType(psiElement, ErlangModule.class) != null;
  }

  @NotNull
  static List<ErlangRecordDefinition> getErlangRecordFromIncludes(@NotNull ErlangFile containingFile, boolean forCompletion, String name) {
    List<ErlangInclude> includes = containingFile.getIncludes();

    List<ErlangRecordDefinition> fromIncludes = new ArrayList<ErlangRecordDefinition>();
    for (ErlangInclude include : includes) {
      PsiElement string = include.getIncludeString();
      if (string != null) {
        String includeFilePath = string.getText().replaceAll("\"", "");

        List<ErlangFile> justAppend = justAppend(containingFile, includeFilePath);
        List<ErlangFile> byWildCard = findByWildCard(containingFile, includeFilePath);

        List<ErlangFile> files = ContainerUtil.concat(justAppend, byWildCard);

        for (ErlangFile file : files) {
          if (!forCompletion) {
            ErlangRecordDefinition recordFromIncludeFile = file.getRecord(name);
            fromIncludes.addAll(recordFromIncludeFile == null ? ContainerUtil.<ErlangRecordDefinition>emptyList() : ContainerUtil.list(recordFromIncludeFile));
          }
          else {
            fromIncludes.addAll(file.getRecords());
          }
        }
      }
    }
    return fromIncludes;
  }

  @NotNull
  static List<ErlangFile> filesFromInclude(@NotNull ErlangInclude include) {
    PsiElement string = include.getIncludeString();
    PsiFile containingFile = include.getContainingFile();
    if (string != null) {
      String includeFilePath = string.getText().replaceAll("\"", "");
      return ContainerUtil.concat(justAppend(containingFile, includeFilePath), findByWildCard(containingFile, includeFilePath));
    }
    return Collections.emptyList();
  }

  @NotNull
  static List<ErlangMacrosDefinition> getErlangMacrosesFromIncludes(@NotNull ErlangFile containingFile, boolean forCompletion, String name) {
    List<ErlangInclude> includes = containingFile.getIncludes();

    List<ErlangMacrosDefinition> fromIncludes = new ArrayList<ErlangMacrosDefinition>();
    for (ErlangInclude include : includes) {
      PsiElement string = include.getIncludeString();
      if (string != null) {
        String includeFilePath = string.getText().replaceAll("\"", "");

        List<ErlangFile> justAppend = justAppend(containingFile, includeFilePath);
        List<ErlangFile> byWildCard = findByWildCard(containingFile, includeFilePath);

        List<ErlangFile> files = ContainerUtil.concat(justAppend, byWildCard);

        for (ErlangFile file : files) {
          if (!forCompletion) {
            ErlangMacrosDefinition recordFromIncludeFile = file.getMacros(name);
            fromIncludes.addAll(recordFromIncludeFile == null ? ContainerUtil.<ErlangMacrosDefinition>emptyList() : ContainerUtil.list(recordFromIncludeFile));
          }
          else {
            fromIncludes.addAll(file.getMacroses());
          }
        }
      }
    }
    return fromIncludes;
  }

  @NotNull
  private static List<ErlangFile> findByWildCard(@NotNull PsiFile containingFile, @NotNull final String includeFilePath) {
    List<ErlangFile> erlangFiles = new ArrayList<ErlangFile>();
    List<String> split = StringUtil.split(includeFilePath, "/");
    String last = ContainerUtil.iterateAndGetLastItem(split);
    if (last != null) {
      PsiFile[] filesByName = FilenameIndex.getFilesByName(containingFile.getProject(), last,
        GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(containingFile.getProject()), ErlangFileType.HRL, ErlangFileType.INSTANCE));
      List<PsiFile> filter = ContainerUtil.filter(filesByName, new Condition<PsiFile>() {
        @Override
        public boolean value(PsiFile psiFile) {
          VirtualFile virtualFile = psiFile.getVirtualFile();
          if (virtualFile == null) return false;
          String canonicalPath = virtualFile.getCanonicalPath();
          if (canonicalPath == null) return false;
          return canonicalPath.replaceAll("-[\\d\\.\\w-]+/", "/").endsWith(includeFilePath);
        }
      });

      for (PsiFile file : filter) {
        if (file instanceof ErlangFile) {
          erlangFiles.add((ErlangFile) file);
        }
      }
    }
    return erlangFiles;
  }

  @NotNull
  public static List<ErlangFile> justAppend(@NotNull PsiFile containingFile, @NotNull String includeFilePath) {
    List<ErlangFile> erlangFiles = new ArrayList<ErlangFile>();
    VirtualFile virtualFile = containingFile.getOriginalFile().getVirtualFile();
    VirtualFile parent = virtualFile != null ? virtualFile.getParent() : null;
    if (parent == null) return ContainerUtil.emptyList();
    String localPath = PathUtil.getLocalPath(parent);
    String globalPath = localPath + "/" + includeFilePath;

    VirtualFile fileByUrl = LocalFileSystem.getInstance().findFileByPath(globalPath);
    if (fileByUrl == null) return ContainerUtil.emptyList();
    PsiFile file = ((PsiManagerEx) PsiManager.getInstance(containingFile.getProject())).getFileManager().findFile(fileByUrl);
    if (file instanceof ErlangFile) {
      erlangFiles.add((ErlangFile) file);
    }
    return erlangFiles;
  }

  public static PsiElement getNameIdentifier(ErlangMacrosDefinition o) {
    ErlangMacrosName macrosName = o.getMacrosName();
    if (macrosName == null) return o;
    return macrosName;
  }

  public static int getTextOffset(ErlangMacrosDefinition o) {
    if (o.getMacrosName() == null) return 0;
    return getNameIdentifier(o).getTextOffset();
  }

  public static String getName(ErlangMacrosDefinition o) {
    return o.getNameIdentifier().getText();
  }

  public static PsiElement setName(ErlangMacrosDefinition o, String newName) {
    ErlangMacrosName macrosName = o.getMacrosName();
    if (macrosName != null) {
      macrosName.replace(ErlangElementFactory.createMacrosFromText(o.getProject(), newName));
    }
    return o;
  }

  @Nullable
  public static List<ErlangFunction> getExternalFunctionForCompletion(Project project, @NotNull String moduleFileName) {
    PsiFile[] files = FilenameIndex.getFilesByName(project, moduleFileName, GlobalSearchScope.allScope(project));
    List<ErlangFunction> result = new ArrayList<ErlangFunction>();
    for (PsiFile file : files) {
      if (file instanceof ErlangFile) {
        result.addAll(((ErlangFile) file).getFunctions());
      }
    }
    return result;
  }

  public static boolean inFunction(PsiElement position) {
    return PsiTreeUtil.getParentOfType(position, ErlangFunction.class) != null;
  }

  public static String getName(ErlangTypedExpr o) {
    return o.getNameIdentifier().getText();
  }

  public static PsiElement setName(ErlangTypedExpr o, String newName) {
    ErlangQAtom qAtom = o.getQAtom();
    PsiElement atom = qAtom.getAtom();
    if (atom != null) {
      atom.replace(ErlangElementFactory.createQAtomFromText(o.getProject(), newName));
    }
    return o;
  }

  public static PsiElement getNameIdentifier(ErlangTypedExpr o) {
    return o.getQAtom();
  }

  public static int getTextOffset(ErlangTypedExpr o) {
    return o.getNameIdentifier().getTextOffset();
  }
}
