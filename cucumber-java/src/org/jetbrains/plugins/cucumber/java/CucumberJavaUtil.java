// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.cucumber.java;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.junit2.info.LocationUtil;
import com.intellij.find.findUsages.JavaFindUsagesHelper;
import com.intellij.find.findUsages.JavaMethodFindUsagesOptions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.*;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Query;
import com.intellij.util.text.VersionComparatorUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import io.cucumber.cucumberexpressions.CucumberExpressionGenerator;
import io.cucumber.cucumberexpressions.GeneratedExpression;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.MapParameterTypeManager;
import org.jetbrains.plugins.cucumber.java.config.CucumberConfigUtil;
import org.jetbrains.plugins.cucumber.psi.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.intellij.psi.util.PsiTreeUtil.getChildOfType;
import static com.intellij.psi.util.PsiTreeUtil.getChildrenOfTypeAsList;
import static org.jetbrains.plugins.cucumber.CucumberUtil.STANDARD_PARAMETER_TYPES;
import static org.jetbrains.plugins.cucumber.MapParameterTypeManager.DEFAULT;
import static org.jetbrains.plugins.cucumber.java.CucumberJavaVersionUtil.CUCUMBER_CORE_VERSION_1_1;
import static org.jetbrains.plugins.cucumber.java.CucumberJavaVersionUtil.CUCUMBER_CORE_VERSION_4_5;
import static org.jetbrains.plugins.cucumber.java.run.CucumberJavaRunConfigurationProducer.CONFIGURATION_ANNOTATION_NAMES;
import static org.jetbrains.plugins.cucumber.java.run.CucumberJavaRunConfigurationProducer.HOOK_AND_TYPE_ANNOTATION_NAMES;
import static org.jetbrains.plugins.cucumber.java.steps.AnnotationPackageProvider.CUCUMBER_ANNOTATION_PACKAGES;

public final class CucumberJavaUtil {
  private static final Logger LOG = Logger.getInstance(CucumberJavaUtil.class);

  public static final String PARAMETER_TYPE_CLASS = "io.cucumber.cucumberexpressions.ParameterType";

  private static final Map<String, String> JAVA_PARAMETER_TYPES;
  public static final String CUCUMBER_EXPRESSIONS_CLASS_MARKER = "io.cucumber.cucumberexpressions.CucumberExpressionGenerator";

  public static final String CUCUMBER_1_0_MAIN_CLASS = "cucumber.cli.Main";
  public static final String CUCUMBER_1_1_MAIN_CLASS = "cucumber.api.cli.Main";
  public static final String CUCUMBER_4_5_MAIN_CLASS = "io.cucumber.core.cli.Main";

  public static final String PARAMETER_TYPE_ANNOTATION_FQN = "io.cucumber.java.ParameterType";

  private static final CallMatcher FROM_ENUM_METHOD = CallMatcher.anyOf(
    CallMatcher.staticCall("io.cucumber.cucumberexpressions.ParameterType", "fromEnum")
  );

  public static final Set<String> STEP_MARKERS = Set.of("Given", "Then", "And", "But", "When");
  public static final Set<String> HOOK_MARKERS = Set.of("Before", "After");

  static {
    Map<String, String> javaParameterTypes = new HashMap<>();
    javaParameterTypes.put("short", STANDARD_PARAMETER_TYPES.get("int"));
    javaParameterTypes.put("biginteger", STANDARD_PARAMETER_TYPES.get("int"));
    javaParameterTypes.put("bigdecimal", "-?\\d*[.,]\\d+");
    javaParameterTypes.put("byte", STANDARD_PARAMETER_TYPES.get("int"));
    javaParameterTypes.put("double", STANDARD_PARAMETER_TYPES.get("float"));
    javaParameterTypes.put("long", STANDARD_PARAMETER_TYPES.get("int"));

    JAVA_PARAMETER_TYPES = Collections.unmodifiableMap(javaParameterTypes);
  }

  /// - Backslashes become `\\\\`
  /// - Quotes become `\\"`
  public static @NotNull String escapeCucumberRegex(@NotNull String regex) {
    return regex
      .replace("\\\\", "\\")
      .replace("\\\"", "\"");
  }

  public static @NotNull String unescapeCucumberRegex(@NotNull String pattern) {
    return pattern
      .replace("\\", "\\\\")
      .replace("\"", "\\\"");
  }

  public static @NotNull String replaceRegexpWithCucumberExpression(@NotNull String snippet, @NotNull String step) {
    try {
      ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.getDefault());
      CucumberExpressionGenerator generator = new CucumberExpressionGenerator(registry);
      GeneratedExpression result = generator.generateExpressions(step).get(0);
      if (result != null) {
        String cucumberExpression = unescapeCucumberRegex(result.getSource());
        String[] lines = snippet.split("\n");

        int start = lines[0].indexOf('(') + 1;
        lines[0] = lines[0].substring(0, start + 1) + cucumberExpression + "\")";
        return StringUtil.join(lines, "");
      }
    }
    catch (Exception ignored) {
      LOG.warn("Failed to replace regex with Cucumber Expression for step: " + step);
      // TODO(bartekpacia): We should probably return null in this case.
    }
    return snippet;
  }

  private static @NotNull String getCucumberAnnotationSuffix(@NotNull String name) {
    for (String pkg : CUCUMBER_ANNOTATION_PACKAGES) {
      if (name.startsWith(pkg)) {
        return name.substring(pkg.length());
      }
    }
    return "";
  }

  public static @NotNull String getCucumberPendingExceptionFqn(@NotNull PsiElement context) {
    // The commit that changed 'cucumber.api.PendingException' to 'io.cucumber.java.PendingException':
    // https://github.com/cucumber/cucumber-jvm/commits/ee6b693184b463c023a265fe98fa9ab5ab2ce819/java/src/main/java/io/cucumber/java/PendingException.java
    // It was released in cucumber-jvm 5.0.0-RC1
    final String version = CucumberConfigUtil.getCucumberCoreVersion(context);
    if (version == null || version.compareTo(CucumberConfigUtil.CUCUMBER_VERSION_5_0) >= 0) {
      return "io.cucumber.java.PendingException";
    }
    else if (version.compareTo(CucumberConfigUtil.CUCUMBER_VERSION_4_5) >= 0) {
      return "cucumber.api.PendingException";
    }
    return "cucumber.runtime.PendingException";
  }

  private static @Nullable String getAnnotationName(final @NotNull PsiAnnotation annotation) {
    final Ref<String> qualifiedAnnotationName = new Ref<>();
    ApplicationManager.getApplication().runReadAction(() -> {
      String qualifiedName = annotation.getQualifiedName();
      qualifiedAnnotationName.set(qualifiedName);
    });
    return qualifiedAnnotationName.get();
  }

  public static boolean isCucumberStepAnnotation(final @NotNull PsiAnnotation annotation) {
    final String annotationName = getAnnotationName(annotation);
    if (annotationName == null) return false;

    final String annotationSuffix = getCucumberAnnotationSuffix(annotationName);
    if (annotationSuffix.contains(".")) {
      return true;
    }
    return STEP_MARKERS.contains(annotationName);
  }

  public static boolean isCucumberHookAnnotation(final @NotNull PsiAnnotation annotation) {
    final String annotationName = getAnnotationName(annotation);
    if (annotationName == null) return false;

    final String annotationSuffix = getCucumberAnnotationSuffix(annotationName);
    return HOOK_MARKERS.contains(annotationSuffix);
  }

  public static boolean isStepDefinition(final @NotNull PsiMethod method) {
    List<PsiAnnotation> stepAnnotations = getCucumberStepAnnotations(method);
    return !stepAnnotations.isEmpty();
  }

  public static boolean isHook(final @NotNull PsiMethod method) {
    return getCucumberHookAnnotation(method) != null;
  }

  public static boolean isParameterType(@NotNull PsiMethod method) {
    return getParameterTypeAnnotation(method) != null;
  }

  public static boolean isStepDefinitionClass(final @NotNull PsiClass clazz) {
    PsiMethod[] methods = clazz.getAllMethods();
    for (PsiMethod method : methods) {
      if (!getCucumberStepAnnotations(method).isEmpty() || getCucumberHookAnnotation(method) != null) return true;
    }
    return false;
  }

  public static @Nullable PsiAnnotation getParameterTypeAnnotation(@NotNull PsiMethod method) {
    for (PsiAnnotation annotation : method.getAnnotations()) {
      String annotationFqn = getAnnotationName(annotation);
      if (PARAMETER_TYPE_ANNOTATION_FQN.equals(annotationFqn)) {
        return annotation;
      }
    }
    return null;
  }

  /// Returns all Cucumber step annotation (like `@Given`, `@When`, and `@Then`) for `method`.
  ///
  /// Usually, there's only 1 Cucumber step annotation per step definition method, but it's not a hard requirement.
  public static List<PsiAnnotation> getCucumberStepAnnotations(@NotNull PsiMethod method) {
    return getCucumberStepAnnotations(method, null);
  }

  public static @NotNull List<PsiAnnotation> getCucumberStepAnnotations(@NotNull PsiMethod method, @Nullable String annotationClassName) {
    List<PsiAnnotation> result = new ArrayList<>();
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return result;
    }

    final PsiAnnotation[] annotations = method.getModifierList().getAnnotations();

    for (PsiAnnotation annotation : annotations) {
      if (annotation != null &&
          (annotationClassName == null || annotationClassName.equals(annotation.getQualifiedName())) &&
          isCucumberStepAnnotation(annotation)) {
        result.add(annotation);
      }
    }
    return result;
  }

  /**
   * Computes value of Step Definition Annotation. If {@code annotationClassName provided} value of the annotation with corresponding class
   * will be returned. Operations with string constants are also handled.
   */
  public static @NotNull List<String> getStepAnnotationValues(@NotNull PsiMethod method, @Nullable String annotationClassName) {
    List<String> result = new ArrayList<>();
    final List<PsiAnnotation> stepAnnotations = getCucumberStepAnnotations(method, annotationClassName);
    for (PsiAnnotation stepAnnotation : stepAnnotations) {
      String annotationValue = getAnnotationValue(stepAnnotation);
      if (annotationValue != null) {
        result.add(annotationValue);
      }
    }

    return result;
  }

  public static @Nullable String getAnnotationValue(@NotNull PsiAnnotation stepAnnotation) {
    return AnnotationUtil.getDeclaredStringAttributeValue(stepAnnotation, "value");
  }

  public static @Nullable PsiAnnotation getCucumberHookAnnotation(PsiMethod method) {
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return null;
    }

    final PsiAnnotation[] annotations = method.getModifierList().getAnnotations();

    for (PsiAnnotation annotation : annotations) {
      if (annotation != null && isCucumberHookAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  public static @Nullable String getPatternFromStepDefinition(final @NotNull PsiAnnotation stepAnnotation) {
    String result = AnnotationUtil.getStringAttributeValue(stepAnnotation, null);
    if (result != null) {
      result = result.replaceAll("\\\\", "\\\\\\\\");
    }
    return result;
  }

  private static @Nullable String getPackageOfStepDef(GherkinStep[] steps) {
    for (GherkinStep step : steps) {
      final String pack = getPackageOfStep(step);
      if (pack != null) return pack;
    }
    return null;
  }

  public static @NotNull String getPackageOfStepDef(final PsiElement element) {
    PsiFile file = element.getContainingFile();
    if (file instanceof GherkinFile) {
      GherkinFeature feature = getChildOfType(file, GherkinFeature.class);
      if (feature != null) {
        List<GherkinScenario> scenarioList = getChildrenOfTypeAsList(feature, GherkinScenario.class);
        for (GherkinScenario scenario : scenarioList) {
          String result = getPackageOfStepDef(scenario.getSteps());
          if (result != null) {
            return result;
          }
        }

        List<GherkinScenarioOutline> scenarioOutlineList = getChildrenOfTypeAsList(feature, GherkinScenarioOutline.class);
        for (GherkinScenarioOutline scenario : scenarioOutlineList) {
          String result = getPackageOfStepDef(scenario.getSteps());
          if (result != null) {
            return result;
          }
        }
      }
    }
    return "";
  }

  public static String getPackageOfStep(GherkinStep step) {
    for (PsiReference ref : step.getReferences()) {
      ProgressManager.checkCanceled();
      PsiElement refElement = ref.resolve();
      if (refElement instanceof PsiMethod || refElement instanceof PsiMethodCallExpression) {
        PsiClassOwner file = (PsiClassOwner)refElement.getContainingFile();
        final String packageName = file.getPackageName();
        if (StringUtil.isNotEmpty(packageName)) {
          return packageName;
        }
      }
    }
    return null;
  }

  /// Adds `glue` to the `glues` set if it isn't already covered by one of `glues`.
  ///
  /// Returns if the `glue` was added to the `glues` set.
  public static boolean addGlue(String glue, Set<String> glues) {
    boolean covered = false;
    final Set<String> toRemove = new HashSet<>();
    for (String existedGlue : glues) {
      if (glue.startsWith(existedGlue + ".")) {
        covered = true;
        break;
      }
      else if (existedGlue.startsWith(glue + ".")) {
        toRemove.add(existedGlue);
      }
    }

    for (String removing : toRemove) {
      glues.remove(removing);
    }

    if (!covered) {
      glues.add(glue);
      return true;
    }
    return false;
  }

  public static MapParameterTypeManager getAllParameterTypes(@NotNull Module module) {
    Project project = module.getProject();
    PsiManager manager = PsiManager.getInstance(project);

    VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
    PsiDirectory psiDirectory = projectDir != null ? manager.findDirectory(projectDir) : null;
    if (psiDirectory != null) {
      return CachedValuesManager.getCachedValue(psiDirectory, () ->
        CachedValueProvider.Result.create(doGetAllParameterTypes(module), PsiModificationTracker.MODIFICATION_COUNT));
    }

    return DEFAULT;
  }

  private static @NotNull MapParameterTypeManager doGetAllParameterTypes(@NotNull Module module) {
    final GlobalSearchScope dependenciesScope = module.getModuleWithDependenciesAndLibrariesScope(true);

    Map<String, String> values = new HashMap<>();
    Map<String, SmartPsiElementPointer<PsiElement>> declarations = new HashMap<>();

    processParameterTypesDefinedByAnnotation(module, dependenciesScope, values, declarations);
    processParameterTypesDefinedByTypeRegistry(module, dependenciesScope, values, declarations);

    values.putAll(STANDARD_PARAMETER_TYPES);
    values.putAll(JAVA_PARAMETER_TYPES);
    return new MapParameterTypeManager(values, declarations);
  }

  /**
   * Looks for Parameter Type defined by Type Registry and stores Parameter Type's name, value in {@code values}
   * and its SmartPointer in {@code declarations}.
   * <p>
   * For example, the code below defines Parameter Type called "iso-date" that matches expression "\d{4}-\d{2}-\d{2}"
   * <pre>{@code
   *     typeRegistry.defineParameterType(new ParameterType<>(
   *       "iso-date",
   *       "\\d{4}-\\d{2}-\\d{2}",
   *       Date.class,
   *       (String s) -> new SimpleDateFormat("yyyy-mm-dd").parse(s)
   *     ));
   * }</pre>
   */
  private static void processParameterTypesDefinedByTypeRegistry(@NotNull Module module,
                                                                 @NotNull GlobalSearchScope scope,
                                                                 @NotNull Map<String, String> values,
                                                                 @NotNull Map<String, SmartPsiElementPointer<PsiElement>> declarations) {
    CommonProcessors.CollectProcessor<UsageInfo> processor = new CommonProcessors.CollectProcessor<>();
    JavaMethodFindUsagesOptions options = new JavaMethodFindUsagesOptions(scope);

    PsiClass parameterTypeClass = ClassUtil.findPsiClass(PsiManager.getInstance(module.getProject()), PARAMETER_TYPE_CLASS);
    if (parameterTypeClass != null) {
      ProgressManager.getInstance().runProcess(() -> {
        for (PsiMethod method : parameterTypeClass.getMethods()) {
          ProgressManager.checkCanceled();
          if (method.getModifierList().hasModifierProperty(PsiModifier.PUBLIC) &&
              method.getModifierList().hasModifierProperty(PsiModifier.STATIC) || method.isConstructor()) {
            JavaFindUsagesHelper.processElementUsages(method, options, processor);
          }
        }
      }, null);
    }

    for (UsageInfo ui : processor.getResults()) {
      PsiElement element = ui.getElement();
      if (element == null) {
        continue;
      }
      if (element.getParent() instanceof PsiNewExpression newExpression) {
        processParameterTypeFromConstructor(values, declarations, newExpression);
      }
      if (element.getParent() instanceof PsiMethodCallExpression call) {

        processParameterTypeMethodDeclaration(values, declarations, call);
      }
    }
  }

  /**
   * Looks for Parameter Type defined by annotation {@code @ParameterType}
   * and stores Parameter Type's name, value in {@code values} and its SmartPointer in {@code declarations}
   * <p>
   * For example, the code below defines Parameter Type called "color" that matches expression {@code "red|blue|yellow"}
   * <pre>{@code
   *     @ParameterType("red|blue|yellow")
   *     public String color(String color) {
   *         return "Text with color: " + color;
   *     }
   * }</pre>
   */
  private static void processParameterTypesDefinedByAnnotation(@NotNull Module module,
                                                               @NotNull GlobalSearchScope scope,
                                                               @NotNull Map<String, String> values,
                                                               @NotNull Map<String, SmartPsiElementPointer<PsiElement>> declarations) {
    PsiClass parameterTypeAnnotationClass =
      JavaPsiFacade.getInstance(module.getProject()).findClass(PARAMETER_TYPE_ANNOTATION_FQN, scope);
    if (parameterTypeAnnotationClass != null) {
      Query<PsiMethod> parameterTypeMethods = AnnotatedElementsSearch.searchPsiMethods(parameterTypeAnnotationClass, scope);
      for (PsiMethod method : parameterTypeMethods.findAll()) {
        PsiAnnotation parameterTypeAnnotation = getParameterTypeAnnotation(method);
        if (parameterTypeAnnotation != null) {
          String parameterTypeAnnotationValue = getAnnotationValue(parameterTypeAnnotation);
          if (StringUtil.isNotEmpty(parameterTypeAnnotationValue)) {
            values.put(method.getName(), parameterTypeAnnotationValue);
            PsiIdentifier methodNameIdentifier = method.getNameIdentifier();
            if (methodNameIdentifier != null) {
              declarations.put(method.getName(), SmartPointerManager.createPointer(methodNameIdentifier));
            }
          }
        }
      }
    }
  }

  private static void processParameterTypeMethodDeclaration(@NotNull Map<String, String> values,
                                                            @NotNull Map<String, SmartPsiElementPointer<PsiElement>> declarations,
                                                            @NotNull PsiMethodCallExpression call) {
    if (!FROM_ENUM_METHOD.matches(call)) return;
    PsiExpression[] arguments = call.getArgumentList().getExpressions();
    if (arguments.length == 0) return;
    PsiExpression enumClass = arguments[0];
    if (!(enumClass instanceof PsiClassObjectAccessExpression objectAccessExpression)) return;
    PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(objectAccessExpression.getOperand().getType());

    if (psiClass == null || !psiClass.isEnum() || psiClass.getName() == null) return;
    String regex = Arrays.stream(psiClass.getFields()).map(f -> f.getName()).collect(Collectors.joining("|"));

    values.put(psiClass.getName(), regex);

    declarations.put(psiClass.getName(), SmartPointerManager.createPointer(psiClass));
  }

  private static void processParameterTypeFromConstructor(@NotNull Map<String, String> values,
                                                          @NotNull Map<String, SmartPsiElementPointer<PsiElement>> declarations,
                                                          @NotNull PsiNewExpression newExpression) {
    PsiExpressionList arguments = newExpression.getArgumentList();
    if (arguments == null) return;
    PsiExpression[] expressions = arguments.getExpressions();
    if (expressions.length == 0) return;
    PsiConstantEvaluationHelper evaluationHelper = JavaPsiFacade.getInstance(newExpression.getProject()).getConstantEvaluationHelper();

    Object constantValue = evaluationHelper.computeConstantExpression(expressions[0], false);
    if (constantValue == null) return;
    String name = constantValue.toString();

    constantValue = evaluationHelper.computeConstantExpression(expressions[1], false);
    if (constantValue == null) return;
    String value = constantValue.toString();
    values.put(name, value);

    declarations.put(name, SmartPointerManager.createPointer(expressions[0]));
  }

  /**
   * Checks if the Cucumber Expressions library is attached to the project.
   *
   * @return true if step definitions could be written in Cucumber Expressions (from Cucumber v3.0),
   * false in the case of old-style Regexp step definitions.
   * @see <a href="https://github.com/cucumber/cucumber-expressions">Cucumber Expressions on GitHub</a>
   */
  public static boolean isCucumberExpressionsAvailable(@NotNull PsiElement context) {
    PsiLocation<PsiElement> location = new PsiLocation<>(context);
    return LocationUtil.isJarAttached(location, PsiDirectory.EMPTY_ARRAY, CUCUMBER_EXPRESSIONS_CLASS_MARKER);
  }

  /**
   * Check every step and send glue (package name) of its definition to consumer.
   */
  public static void calculateGlueFromGherkinFile(@NotNull GherkinFile gherkinFile, @NotNull Consumer<String> consumer) {
    gherkinFile.accept(new GherkinRecursiveElementVisitor() {
      @Override
      public void visitStep(GherkinStep step) {
        ProgressManager.checkCanceled();
        String glue = getPackageOfStep(step);
        if (glue != null) {
          consumer.accept(glue);
        }
      }
    });
  }

  /**
   * Search for all Cucumber Hooks and sends their glue (package names) to consumer
   */
  public static void calculateGlueFromHooksAndTypes(@NotNull PsiElement element, @NotNull Consumer<String> consumer) {
    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    if (module == null) {
      return;
    }

    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(element.getProject());
    GlobalSearchScope dependenciesScope = module.getModuleWithDependenciesAndLibrariesScope(true);

    for (String fullyQualifiedAnnotationName : HOOK_AND_TYPE_ANNOTATION_NAMES) {
      ProgressManager.checkCanceled();
      PsiClass psiClass = javaPsiFacade.findClass(fullyQualifiedAnnotationName, dependenciesScope);

      if (psiClass != null) {
        Query<PsiMethod> psiMethods = AnnotatedElementsSearch
          .searchPsiMethods(psiClass, GlobalSearchScope.allScope(element.getProject()));
        Collection<PsiMethod> methods = psiMethods.findAll();
        methods.forEach(it -> {
          PsiClassOwner file = (PsiClassOwner)it.getContainingFile();
          String packageName = file.getPackageName();
          if (StringUtil.isNotEmpty(packageName)) {
            consumer.accept(packageName);
          }
        });
      }
    }

    for (String fqn : CONFIGURATION_ANNOTATION_NAMES) {
      ProgressManager.checkCanceled();
      PsiClass psiClass = javaPsiFacade.findClass(fqn, dependenciesScope);
      if (psiClass == null) {
        continue;
      }
      Collection<PsiClass> psiClasses =
        AnnotatedElementsSearch.searchPsiClasses(psiClass, GlobalSearchScope.allScope(element.getProject())).findAll();
      psiClasses.forEach(it -> {
        PsiClassOwner file = (PsiClassOwner)it.getContainingFile();
        String packageName = file.getPackageName();
        if (StringUtil.isNotEmpty(packageName)) {
          consumer.accept(packageName);
        }
      });
    }
  }

  /**
   * @return The class used to run Cucumber tests for {@code cucumberCoreVersion}.
   */
  public static @NotNull String getCucumberMainClass(@NotNull String cucumberCoreVersion) {
    if (VersionComparatorUtil.compare(cucumberCoreVersion, CUCUMBER_CORE_VERSION_4_5) >= 0) {
      return CUCUMBER_4_5_MAIN_CLASS;
    }
    if (VersionComparatorUtil.compare(cucumberCoreVersion, CUCUMBER_CORE_VERSION_1_1) >= 0) {
      return CUCUMBER_1_1_MAIN_CLASS;
    }
    return CUCUMBER_1_0_MAIN_CLASS;
  }
}
