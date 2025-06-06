// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.flex.model.bc.impl;

import com.intellij.flex.model.bc.CompilerOptionInfo;
import com.intellij.flex.model.bc.JpsFlexCompilerOptions;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.ex.JpsElementBase;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class JpsFlexCompilerOptionsImpl extends JpsElementBase<JpsFlexCompilerOptionsImpl> implements JpsFlexCompilerOptions {
  private final @NotNull Map<String, String> myOptions = new HashMap<>();
  private @NotNull ResourceFilesMode myResourceFilesMode = ResourceFilesMode.All;
  private @NotNull String myFilesToIncludeInSWC = "";
  private @NotNull String myAdditionalConfigFilePath = "";
  private @NotNull String myAdditionalOptions = "";

  JpsFlexCompilerOptionsImpl() {
  }

  private JpsFlexCompilerOptionsImpl(final JpsFlexCompilerOptionsImpl original) {
    myOptions.putAll(original.myOptions);
    myResourceFilesMode = original.myResourceFilesMode;
    myFilesToIncludeInSWC = original.myFilesToIncludeInSWC;
    myAdditionalConfigFilePath = original.myAdditionalConfigFilePath;
    myAdditionalOptions = original.myAdditionalOptions;
  }

  @Override
  public @NotNull JpsFlexCompilerOptionsImpl createCopy() {
    return new JpsFlexCompilerOptionsImpl(this);
  }

// ------------------------------------

  @Override
  public @Nullable String getOption(@NotNull String name) {
    return myOptions.get(name);
  }

  @Override
  public @NotNull Map<String, String> getAllOptions() {
    return Collections.unmodifiableMap(myOptions);
  }

  @Override
  public @NotNull ResourceFilesMode getResourceFilesMode() {
    return myResourceFilesMode;
  }

  @Override
  public void setResourceFilesMode(final @NotNull ResourceFilesMode resourceFilesMode) {
    myResourceFilesMode = resourceFilesMode;
  }

  @Override
  public @NotNull Collection<String> getFilesToIncludeInSWC() {
    if (myFilesToIncludeInSWC.isEmpty()) return Collections.emptyList();
    return StringUtil.split(myFilesToIncludeInSWC, CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
  }

  @Override
  public @NotNull String getAdditionalConfigFilePath() {
    return myAdditionalConfigFilePath;
  }

  @Override
  public @NotNull String getAdditionalOptions() {
    return myAdditionalOptions;
  }

  @Override
  public void setAdditionalOptions(final @NotNull String additionalOptions) {
    myAdditionalOptions = additionalOptions;
  }

// ------------------------------------

  public State getState(/*final @Nullable ComponentManager componentManager*/) {
    State state = new State();
    /*putOptionsCollapsingPaths(myOptions, state.options, componentManager);*/
    state.options.putAll(myOptions);

    state.resourceFilesMode = myResourceFilesMode;
    //state.filesToIncludeInSWC = FlexIdeBuildConfigurationImpl.collapsePaths(componentManager, myFilesToIncludeInSWC);
    state.filesToIncludeInSWC = myFilesToIncludeInSWC;
    state.additionalConfigFilePath = myAdditionalConfigFilePath;
    state.additionalOptions = myAdditionalOptions;
    return state;
  }

  public void loadState(State state) {
    assert myOptions.isEmpty();

    myOptions.putAll(state.options);
    // filter out options that are not known in current IDEA version
    /*
    for (Map.Entry<String, String> entry : state.options.entrySet()) {
      if (CompilerOptionInfo.idExists(entry.getKey())) {
        // no need in expanding paths, it is done automatically even if macros is not in the beginning of the string
        myOptions.put(entry.getKey(), entry.getValue());
      }
    }
    */
    myResourceFilesMode = state.resourceFilesMode;
    myFilesToIncludeInSWC = state.filesToIncludeInSWC;
    myAdditionalConfigFilePath = state.additionalConfigFilePath;
    myAdditionalOptions = state.additionalOptions;
  }

  @Tag("compiler-options")
  public static class State {
    @Property(surroundWithTag = false)
    @MapAnnotation
    public Map<String, String> options = new HashMap<>();
    public ResourceFilesMode resourceFilesMode = ResourceFilesMode.All;
    public String filesToIncludeInSWC = "";
    public String additionalConfigFilePath = "";
    public String additionalOptions = "";
  }
}
