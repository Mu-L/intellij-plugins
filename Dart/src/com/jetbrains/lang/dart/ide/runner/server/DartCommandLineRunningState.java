// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.lang.dart.ide.runner.server;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.CommandLineTokenizer;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderImpl;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.net.NetUtils;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.coverage.DartCoverageProgramRunner;
import com.jetbrains.lang.dart.ide.devtools.DartDevToolsService;
import com.jetbrains.lang.dart.ide.errorTreeView.DartProblemsView;
import com.jetbrains.lang.dart.ide.runner.DartConsoleFilter;
import com.jetbrains.lang.dart.ide.runner.DartExecutionHelper;
import com.jetbrains.lang.dart.ide.runner.DartRelativePathsConsoleFilter;
import com.jetbrains.lang.dart.ide.runner.base.DartRunConfiguration;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DartCommandLineRunningState extends CommandLineState {
  protected final @NotNull DartCommandLineRunnerParameters myRunnerParameters;
  private final Collection<Consumer<String>> myObservatoryUrlConsumers = new ArrayList<>();

  public DartCommandLineRunningState(final @NotNull ExecutionEnvironment env) throws ExecutionException {
    super(env);
    myRunnerParameters = ((DartRunConfiguration)env.getRunProfile()).getRunnerParameters().clone();

    final Project project = env.getProject();
    try {
      myRunnerParameters.check(project);
    }
    catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    final TextConsoleBuilder builder = getConsoleBuilder();
    if (builder instanceof TextConsoleBuilderImpl) {
      ((TextConsoleBuilderImpl)builder).setUsePredefinedMessageFilter(false);
    }

    try {
      builder.addFilter(new DartConsoleFilter(project, myRunnerParameters.getDartFileOrDirectory()));
      builder.addFilter(new DartRelativePathsConsoleFilter(project, myRunnerParameters.computeProcessWorkingDirectory(project)));
      builder.addFilter(new UrlFilter());
    }
    catch (RuntimeConfigurationError e) { /* can't happen because already checked */}
  }

  public void addObservatoryUrlConsumer(final @NotNull Consumer<String> consumer) {
    myObservatoryUrlConsumers.add(consumer);
  }

  @Override
  protected AnAction @NotNull [] createActions(final ConsoleView console, final ProcessHandler processHandler, final Executor executor) {
    // These actions are effectively added only to the Run tool window. For Debug see DartCommandLineDebugProcess.registerAdditionalActions()
    final List<AnAction> actions = new ArrayList<>(Arrays.asList(super.createActions(console, processHandler, executor)));
    addObservatoryActions(actions, processHandler);
    return actions.toArray(AnAction.EMPTY_ARRAY);
  }

  protected void addObservatoryActions(List<AnAction> actions, final ProcessHandler processHandler) {
    actions.add(new Separator());

    final OpenDartObservatoryUrlAction openObservatoryAction =
      new OpenDartObservatoryUrlAction(null, () -> !processHandler.isProcessTerminated());
    addObservatoryUrlConsumer(openObservatoryAction::setUrl);

    actions.add(openObservatoryAction);
  }

  @Override
  protected @NotNull ProcessHandler startProcess() throws ExecutionException {
    final GeneralCommandLine commandLine = createCommandLine();

    // Workaround for "Observatory listening on ..." message that is concatenated (without line break) with the message following it
    final OSProcessHandler processHandler = new ColoredProcessHandler(commandLine) {
      @Override
      public void coloredTextAvailable(@NotNull String text, @NotNull Key attributes) {
        if (text.startsWith(DartConsoleFilter.OBSERVATORY_LISTENING_ON) || text.startsWith(DartConsoleFilter.DART_VM_LISTENING_ON)) {
          text += "\n";
        }
        super.coloredTextAvailable(text, attributes);
      }
    };

    processHandler.addProcessListener(new ProcessListener() {
      @Override
      public void onTextAvailable(final @NotNull ProcessEvent event, final @NotNull Key outputType) {
        final String prefix = DartConsoleFilter.OBSERVATORY_LISTENING_ON + "http://";
        final String prefix2 = DartConsoleFilter.DART_VM_LISTENING_ON + "http://";
        final String text = event.getText().trim();
        if (text.startsWith(prefix)) {
          processHandler.removeProcessListener(this);
          final String url = "http://" + text.substring(prefix.length());
          for (Consumer<String> consumer : myObservatoryUrlConsumers) {
            consumer.consume(url);
          }
        }
        else if (text.startsWith(prefix2)) {
          processHandler.removeProcessListener(this);
          final String url = "http://" + text.substring(prefix2.length());
          for (Consumer<String> consumer : myObservatoryUrlConsumers) {
            consumer.consume(url);
          }
        }
      }
    });

    // Check for and display any analysis errors when we launch a Dart app.
    final Project project = getEnvironment().getProject();
    try {
      final DartRunConfiguration dartRunConfiguration = (DartRunConfiguration)getEnvironment().getRunProfile();
      final VirtualFile launchFile = dartRunConfiguration.getRunnerParameters().getDartFileOrDirectory();
      final String message = DartBundle.message("analysis.issues.may.affect.the.execution",
                                                DartProblemsView.OPEN_DART_ANALYSIS_LINK, dartRunConfiguration.getName());
      DartExecutionHelper.displayIssues(project, launchFile, message, dartRunConfiguration.getIcon());
    }
    catch (RuntimeConfigurationError error) {
      DartExecutionHelper.clearIssueNotifications(project);
    }

    ProcessTerminatedListener.attach(processHandler, getEnvironment().getProject());
    return processHandler;
  }

  private GeneralCommandLine createCommandLine() throws ExecutionException {
    final DartSdk sdk = DartSdk.getDartSdk(getEnvironment().getProject());
    if (sdk == null) {
      throw new ExecutionException(DartBundle.message("dart.sdk.is.not.configured"));
    }

    final GeneralCommandLine commandLine = new GeneralCommandLine()
      .withWorkDirectory(myRunnerParameters.computeProcessWorkingDirectory(getEnvironment().getProject()));
    commandLine.setCharset(StandardCharsets.UTF_8);
    setupExePath(commandLine, sdk);
    commandLine.getEnvironment().putAll(myRunnerParameters.getEnvs());
    commandLine
      .withParentEnvironmentType(myRunnerParameters.isIncludeParentEnvs() ? ParentEnvironmentType.CONSOLE : ParentEnvironmentType.NONE);
    setupParameters(sdk, commandLine);

    return commandLine;
  }

  protected void setupExePath(@NotNull GeneralCommandLine commandLine, @NotNull DartSdk sdk) {
    commandLine.setExePath(DartSdkUtil.getDartExePath(sdk));
  }

  private void setupParameters(final @NotNull DartSdk sdk,
                               final @NotNull GeneralCommandLine commandLine) throws ExecutionException {
    int customObservatoryPort = -1;

    final String vmOptions = myRunnerParameters.getVMOptions();
    if (vmOptions != null) {
      final StringTokenizer vmOptionsTokenizer = new CommandLineTokenizer(vmOptions);
      while (vmOptionsTokenizer.hasMoreTokens()) {
        final String vmOption = vmOptionsTokenizer.nextToken();
        addVmOption(sdk, commandLine, vmOption);

        try {
          if (vmOption.equals("--enable-vm-service") || vmOption.equals("--observe")) {
            customObservatoryPort = 8181; // default port, see https://dart.dev/tools/dart-devtools#3-start-the-target-app
          }
          else if (vmOption.startsWith("--enable-vm-service:")) {
            customObservatoryPort = parseIntBeforeSlash(vmOption.substring("--enable-vm-service:".length()));
          }
          else if (vmOption.startsWith("--observe:")) {
            customObservatoryPort = parseIntBeforeSlash(vmOption.substring("--observe:".length()));
          }
        }
        catch (NumberFormatException ignore) {/**/}
      }
    }

    if (myRunnerParameters.isCheckedModeOrEnableAsserts()) {
      if (StringUtil.compareVersionNumbers(sdk.getVersion(), "2") < 0) {
        addVmOption(sdk, commandLine, "--checked");
      }
      else {
        addVmOption(sdk, commandLine, "--enable-asserts");
      }
    }

    if (DefaultDebugExecutor.EXECUTOR_ID.equals(getEnvironment().getExecutor().getId())) {
      addVmOption(sdk, commandLine, "--pause_isolates_on_start");
    }

    if (customObservatoryPort <= 0 && DefaultDebugExecutor.EXECUTOR_ID.equals(getEnvironment().getExecutor().getId())) {
      try {
        addVmOption(sdk, commandLine, "--enable-vm-service:" + NetUtils.findAvailableSocketPort());
      }
      catch (IOException e) {
        throw new ExecutionException(e);
      }

      if (getEnvironment().getRunner() instanceof DartCoverageProgramRunner) {
        addVmOption(sdk, commandLine, "--pause-isolates-on-exit");
      }
    }

    if (DartDevToolsService.getInstance(getEnvironment().getProject()).getDevToolsHostAndPort() != null) {
      addVmOption(sdk, commandLine, "--no-serve-devtools");
    }

    appendParamsAfterVmOptionsBeforeArgs(commandLine);

    final String arguments = myRunnerParameters.getArguments();
    if (arguments != null) {
      StringTokenizer argumentsTokenizer = new CommandLineTokenizer(arguments);
      while (argumentsTokenizer.hasMoreTokens()) {
        commandLine.addParameter(argumentsTokenizer.nextToken());
      }
    }
  }

  protected void appendParamsAfterVmOptionsBeforeArgs(final @NotNull GeneralCommandLine commandLine) throws ExecutionException {
    final VirtualFile dartFile;
    try {
      dartFile = myRunnerParameters.getDartFileOrDirectory();
    }
    catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    commandLine.addParameter(FileUtil.toSystemDependentName(dartFile.getPath()));
  }

  protected void addVmOption(@NotNull DartSdk sdk, @NotNull GeneralCommandLine commandLine, @NotNull String option) {
    commandLine.addParameter(option);
  }

  private static int parseIntBeforeSlash(final @NotNull String s) throws NumberFormatException {
    // "5858" or "5858/0.0.0.0"
    final int index = s.indexOf('/');
    return Integer.parseInt(index > 0 ? s.substring(0, index) : s);
  }
}
